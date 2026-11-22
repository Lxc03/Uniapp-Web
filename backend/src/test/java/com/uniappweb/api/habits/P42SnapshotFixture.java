package com.uniappweb.api.habits;

import java.time.Clock;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.uniappweb.acceptance.P42TestApplication.*;

/**
 * 仅在隔离验收入口替换业务适配器，对指定键计时和注入故障。
 *
 * @see "docs/decisions(决策).md#D14"
 */
public final class P42SnapshotFixture extends HabitSnapshotCache {

    /**
     * 复用真实 Redis 和同一个测试业务时钟。
     *
     * @param redis 当前测试 Redis 客户端
     * @param mapper 状态编解码器
     * @param clock P3 测试时钟
     */
    public P42SnapshotFixture(StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        super(redis, mapper, clock);
    }

    @Override
    List<String> read(List<String> keys) {
        if (keys.stream().anyMatch(key -> selected("fail-read", key))) {
            throw new QueryTimeoutException("Isolated snapshot read fault");
        }
        long before = System.nanoTime();
        var result = super.read(keys);
        long after = System.nanoTime();
        for (int i = 0; i < keys.size(); i++) {
            if (selected("track-read", keys.get(i))) {
                String value = result.get(i);
                note("reads", before + " " + after + " " + (value == null ? "miss" : value));
            }
        }
        return result;
    }

    @Override
    Long publish(String key, String value, long expiry) {
        if (selected("fail-fill", key)) throw new QueryTimeoutException("Isolated snapshot fill fault");
        boolean held = holdFill(key, expiry);
        long before = System.nanoTime();
        Long result = super.publish(key, value, expiry);
        if (held) note("fill-result", expiry + " " + before + " " + System.nanoTime() + " " + result);
        return result;
    }

    @Override
    void remove(List<String> keys) {
        if (keys.stream().anyMatch(key -> selected("fail-delete", key))) {
            throw new QueryTimeoutException("Isolated snapshot deletion fault");
        }
        super.remove(keys);
    }
}
