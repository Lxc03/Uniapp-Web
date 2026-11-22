package com.uniappweb.api.habits;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.uniappweb.api.auth.AuthFailure;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import static com.uniappweb.api.auth.AuthFailure.Kind.*;

/**
 * 通过 JdbcTemplate 查询打卡与连续天数，通过短写事务按项和业务日提交记录。
 * 历史日期核实只读取所属项及其已经提交的记录。
 *
 * @see "docs/decisions(决策).md#D9"
 * @see "docs/decisions(决策).md#D10"
 * @see "docs/decisions(决策).md#D11"
 * @see "docs/decisions(决策).md#D12"
 * @see "docs/decisions(决策).md#D13"
 */
@Component
final class CheckIns {

    private static final Pattern DAILY_KEY = Pattern.compile(
            " for key '(?:habit_check_ins\\.)?uk_check_ins_habit_date'\\z");
    // 一次初始尝试和两次锁冲突重试，避免长期占用请求。
    private static final int WRITE_ATTEMPTS = 3;
    private static final int MYSQL_DUPLICATE_KEY = 1062;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final String HISTORY = """
            SELECT h.id, c.id AS check_in_id,
                   c.business_date, c.created_at AS recorded_at
            FROM habits h
            LEFT JOIN habit_check_ins c ON c.habit_id = h.id AND c.business_date <= ?
            WHERE h.user_id = ?
            """;

    @Schema(description = "同一个观察日下的打卡状态")
    record State(@Schema(description = "此请求采样的服务端业务日", example = "2026-11-10") LocalDate businessDate,
                 @Schema(description = "观察日当天是否有已提交记录") boolean checkedInToday,
                 @Schema(description = "截至观察日按连续自然日计算的天数", example = "1") int streakDays) {}

    @Schema(description = "列表中的打卡项及当前业务日状态")
    record Item(String id, String name, Instant createdAt,
                @Schema(description = "列表观察日当天是否已打卡") boolean checkedInToday,
                @Schema(description = "截至列表观察日的连续打卡天数") int streakDays) {}

    @Schema(description = "单个打卡项在观察日的状态")
    record Status(String habitId, State state) {}

    @Schema(description = "已提交的打卡记录，日期可以早于本次观察日")
    record Entry(@Schema(description = "十进制打卡记录 ID", example = "27") String id,
                 @Schema(description = "十进制打卡项 ID", example = "12") String habitId,
                 @Schema(description = "记录实际归属的业务日", example = "2026-11-09") LocalDate checkInDate,
                 @Schema(description = "写入的 UTC 审计时刻", example = "2026-11-09T15:59:59Z") Instant recordedAt) {}

    @Schema(description = "写入或重试核实结果")
    record Outcome(@Schema(description = "首次创建或读取已有记录",
                           allowableValues = {"CREATED", "ALREADY_RECORDED"}) String outcome,
                   Entry checkIn, State state) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transaction;
    private final Habits habits;
    private final HabitSnapshotCache cache;

    CheckIns(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, TransactionTemplate transaction,
             Habits habits, HabitSnapshotCache cache) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.transaction = transaction;
        this.habits = habits;
        this.cache = cache;
    }

    List<Item> list(long userId, LocalDate day) {
        var items = habits.list(userId);
        var ids = items.stream().map(item -> Long.parseLong(item.id())).toList();
        var states = cache.states(userId, ids, day, misses -> historyStates(userId, day, misses));
        return items.stream().map(item -> {
            State state = states.get(Long.parseLong(item.id()));
            if (state == null) throw new AuthFailure(HABIT_DATABASE_UNAVAILABLE);
            return new Item(item.id(), item.name(), item.createdAt(), state.checkedInToday(), state.streakDays());
        }).toList();
    }

    private Map<Long, State> historyStates(long userId, LocalDate day, List<Long> ids) {
        try {
            // [D12-A][D14-A] 只查缺失且归属当前用户的项；这是取 Redis TIME 后的新读快照。
            return namedJdbc.query("""
                    SELECT h.id, c.business_date
                    FROM habits h LEFT JOIN habit_check_ins c
                      ON c.habit_id = h.id AND c.business_date <= :day
                    WHERE h.user_id = :owner AND h.id IN (:ids)
                    ORDER BY h.id, c.business_date DESC
                    """, Map.of("day", day, "owner", userId, "ids", ids), rs -> {
                var streaks = new HashMap<Long, Streak>();
                while (rs.next()) {
                    streaks.computeIfAbsent(rs.getLong("id"), unused -> new Streak(day))
                            .add(rs.getObject("business_date", LocalDate.class));
                }
                var states = new HashMap<Long, State>();
                streaks.forEach((id, streak) -> states.put(id, streak.state()));
                return states;
            });
        } catch (DataAccessException ignored) {
            throw new AuthFailure(HABIT_DATABASE_UNAVAILABLE);
        }
    }

    Status status(long userId, long habitId, LocalDate day) {
        habits.requireOwned(userId, habitId);
        State state = cache.states(userId, List.of(habitId), day,
                misses -> Map.of(habitId, observe(userId, habitId, day, null).state())).get(habitId);
        return new Status(Long.toString(habitId), state);
    }

    Outcome checkIn(long userId, long habitId, LocalDate day, LocalDate expectedDay) {
        boolean sameDay = day.equals(expectedDay);
        boolean created = sameDay && insert(userId, habitId, day);
        if (created) cache.invalidate(userId, habitId, day);
        // [D10-A] 完成提交或整笔回滚后，在新的非锁定快照读回赢家记录，见 docs/decisions(决策).md#D10。
        var result = observe(userId, habitId, day, expectedDay);
        if (result.entry() == null) {
            throw new AuthFailure(sameDay ? CHECKIN_DATABASE_UNAVAILABLE : CHECKIN_BUSINESS_DATE_CHANGED);
        }
        return new Outcome(created ? "CREATED" : "ALREADY_RECORDED", result.entry(), result.state());
    }

    private boolean insert(long userId, long habitId, LocalDate day) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                transaction.executeWithoutResult(status -> {
                    // [D10-A] 持有父项共享锁，使写入与归属校验处于同一事务，见 docs/decisions(决策).md#D10。
                    boolean owned = Boolean.TRUE.equals(jdbc.query("""
                            SELECT id FROM habits WHERE id = ? AND user_id = ? FOR SHARE
                            """, rs -> { return rs.next(); }, habitId, userId));
                    if (!owned) throw new AuthFailure(HABIT_NOT_FOUND);
                    jdbc.update("INSERT INTO habit_check_ins (habit_id, business_date) VALUES (?, ?)", habitId, day);
                });
                return true;
            } catch (DataAccessException failure) {
                if (failure.getMostSpecificCause() instanceof SQLException sql) {
                    int error = sql.getErrorCode();
                    // 只识别每日唯一约束，其他完整性错误走数据库故障。
                    if (error == MYSQL_DUPLICATE_KEY && DAILY_KEY.matcher(sql.getMessage()).find()) return false;
                    if ((error == MYSQL_LOCK_WAIT_TIMEOUT || error == MYSQL_DEADLOCK)
                            && attempt < WRITE_ATTEMPTS) continue;
                }
                throw new AuthFailure(CHECKIN_DATABASE_UNAVAILABLE);
            } catch (TransactionException ignored) {
                throw new AuthFailure(CHECKIN_DATABASE_UNAVAILABLE);
            }
        }
        throw new AuthFailure(CHECKIN_DATABASE_UNAVAILABLE);
    }

    private record Observation(Entry entry, State state) {}

    private Observation observe(long userId, long habitId, LocalDate day, LocalDate expectedDay) {
        try {
            return jdbc.query(HISTORY + " AND h.id = ? ORDER BY c.business_date DESC", rs -> {
                if (!rs.next()) throw new AuthFailure(HABIT_NOT_FOUND);
                var streak = new Streak(day);
                Entry entry = null;
                do {
                    LocalDate date = rs.getObject("business_date", LocalDate.class);
                    streak.add(date);
                    if (date != null && date.equals(expectedDay)) {
                        entry = new Entry(rs.getString("check_in_id"), Long.toString(habitId), date,
                                rs.getTimestamp("recorded_at").toInstant());
                    }
                } while (rs.next());
                return new Observation(entry, streak.state());
            }, day, userId, habitId);
        } catch (DataAccessException ignored) {
            throw new AuthFailure(CHECKIN_DATABASE_UNAVAILABLE);
        }
    }

    /** 同一批 SQL 结果用于计算今日状态和连续天数。参见 D9。 */
    private static final class Streak {
        private final LocalDate day;
        private LocalDate next;
        private boolean checkedToday;
        private int streak;

        Streak(LocalDate day) {
            this.day = day;
            next = day.minusDays(1);
        }

        void add(LocalDate date) {
            if (day.equals(date)) {
                checkedToday = true;
                streak++;
            } else if (next.equals(date)) {
                streak++;
                next = next.minusDays(1);
            }
        }

        State state() {
            return new State(day, checkedToday, streak);
        }
    }
}
