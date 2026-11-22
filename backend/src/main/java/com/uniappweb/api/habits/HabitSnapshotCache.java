package com.uniappweb.api.habits;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import static com.uniappweb.api.habits.BusinessTime.ZONE;

/**
 * 按已校验的用户、项和观察日缓存同一份 MySQL 状态。缓存失败时保留权威读取路径。
 *
 * @see "docs/decisions(决策).md#D12"
 * @see "docs/decisions(决策).md#D13"
 * @see "docs/decisions(决策).md#D14"
 */
@Component
class HabitSnapshotCache {

    private static final Logger LOG = LoggerFactory.getLogger(HabitSnapshotCache.class);
    private static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final String PREFIX = "habit:snapshot:v1:";
    private static final DefaultRedisScript<Long> FILL = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            if now >= tonumber(ARGV[2]) then
                return -1
            end
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PXAT', ARGV[2]) then
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    HabitSnapshotCache(StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    Map<Long, CheckIns.State> states(long userId, List<Long> ids, LocalDate day,
            Function<List<Long>, Map<Long, CheckIns.State>> loadHistory) {
        if (ids.isEmpty()) return Map.of();
        var correlation = UUID.randomUUID();
        if (!currentDay().equals(day)) {
            // 正常缓存活动用 DEBUG，标记回源理由和项数，不输出会话或项 ID。
            LOG.debug("habit_cache op={} observation={} event=history day_changed count={}", correlation, day, ids.size());
            return loadHistory.apply(ids);
        }

        List<String> keys = ids.stream().map(id -> key(userId, id, day)).toList();
        List<String> values;
        try {
            values = read(keys);
            if (values == null || values.size() != ids.size()) {
                LOG.debug("habit_cache op={} observation={} event=history invalid_read count={}", correlation, day, ids.size());
                return loadHistory.apply(ids);
            }
        } catch (DataAccessException ignored) {
            LOG.debug("habit_cache op={} observation={} event=history read_failed count={}", correlation, day, ids.size());
            return loadHistory.apply(ids);
        }

        var states = new HashMap<Long, CheckIns.State>();
        var misses = new ArrayList<Long>();
        for (int i = 0; i < ids.size(); i++) {
            var state = decode(values.get(i), day);
            if (state == null) misses.add(ids.get(i));
            else states.put(ids.get(i), state);
        }
        LOG.debug("habit_cache op={} observation={} event=lookup hits={} misses={}", correlation, day, states.size(), misses.size());
        if (misses.isEmpty()) return states;

        // [D14-A] 先锁定 Redis 到期点，再开启新的 MySQL SELECT，见 docs/decisions(决策).md#D14。
        long expiry = deadline(day);
        LOG.debug("habit_cache op={} observation={} event=history count={}", correlation, day, misses.size());
        var loaded = loadHistory.apply(misses);
        states.putAll(loaded);
        if (expiry > 0 && currentDay().equals(day)) {
            for (var entry : loaded.entrySet()) {
                try {
                    Long result = publish(key(userId, entry.getKey(), day),
                            mapper.writeValueAsString(entry.getValue()), expiry);
                    LOG.debug("habit_cache op={} observation={} event=fill result={} expiresAt={}",
                            correlation, day, result, expiry);
                } catch (DataAccessException | JsonProcessingException ignored) {
                    LOG.debug("habit_cache op={} observation={} event=fill_failed", correlation, day);
                }
            }
        }
        return states;
    }

    void invalidate(long userId, long habitId, LocalDate recordedDay) {
        // [D13-A] 已提交后用缓存维护时钟定位仍在用的观察日，原记录日不变。
        LocalDate observedDay = currentDay();
        String recorded = key(userId, habitId, recordedDay);
        List<String> keys = recordedDay.equals(observedDay)
                ? List.of(recorded) : List.of(recorded, key(userId, habitId, observedDay));
        var correlation = UUID.randomUUID();
        try {
            remove(keys);
            LOG.debug("habit_cache op={} event=invalidated recorded={} observation={} keys={}",
                    correlation, recordedDay, observedDay, keys.size());
        } catch (DataAccessException ignored) {
            // 失效失败可由绝对期限收敛。WARN 便于排查，且不携带 Redis 异常或连接详情。
            LOG.warn("habit_cache op={} event=invalidation_failed recorded={} observation={} keys={}",
                    correlation, recordedDay, observedDay, keys.size());
        }
    }

    private long deadline(LocalDate day) {
        try {
            Long redisNow = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
            ZonedDateTime businessNow = clock.instant().atZone(ZONE);
            if (redisNow != null && businessNow.toLocalDate().equals(day)) {
                long left = Duration.between(businessNow, day.plusDays(1).atStartOfDay(ZONE)).toMillis();
                if (left > 0) return redisNow + Math.min(left, MAX_AGE.toMillis());
            }
        } catch (DataAccessException ignored) {
            LOG.debug("habit_cache observation={} event=clock_unavailable", day);
        }
        return 0;
    }

    private CheckIns.State decode(String value, LocalDate day) {
        if (value == null) return null;
        try {
            var node = mapper.readTree(value);
            if (node == null || !day.toString().equals(node.path("businessDate").textValue())) return null;
            var checked = node.path("checkedInToday");
            var streak = node.path("streakDays");
            if (!checked.isBoolean() || !streak.isIntegralNumber() || !streak.canConvertToInt() || streak.intValue() < 0) {
                return null;
            }
            return new CheckIns.State(day, checked.booleanValue(), streak.intValue());
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    List<String> read(List<String> keys) {
        return redis.opsForValue().multiGet(keys);
    }

    Long publish(String key, String value, long expiry) {
        return redis.execute(FILL, List.of(key), value, Long.toString(expiry));
    }

    void remove(List<String> keys) {
        redis.delete(keys);
    }

    private LocalDate currentDay() {
        return clock.instant().atZone(ZONE).toLocalDate();
    }

    private static String key(long userId, long habitId, LocalDate day) {
        return PREFIX + userId + ":" + habitId + ":" + day;
    }
}
