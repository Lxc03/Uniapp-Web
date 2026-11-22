package com.uniappweb.api.habits;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import com.uniappweb.api.auth.AuthFailure;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import static com.uniappweb.api.auth.AuthFailure.Kind.HABIT_DATABASE_UNAVAILABLE;
import static com.uniappweb.api.auth.AuthFailure.Kind.HABIT_NAME_CONFLICT;
import static com.uniappweb.api.auth.AuthFailure.Kind.HABIT_NOT_FOUND;
import static com.uniappweb.api.auth.AuthFailure.Kind.CHECKIN_DATABASE_UNAVAILABLE;

/**
 * 保存打卡项并提供列表与归属校验。MySQL 唯一约束裁决同一用户的并发重名。
 *
 * @see "docs/decisions(决策).md#D7"
 * @see "docs/decisions(决策).md#D8"
 */
@Component
final class Habits {

    private static final Pattern NAME_CONSTRAINT = Pattern.compile(
            " for key '(?:habits\\.)?uk_habits_user_name'\\z");
    private static final RowMapper<Habit> ROW = (rs, rowNumber) -> new Habit(rs.getString("id"),
            rs.getString("name"), rs.getTimestamp("created_at").toInstant());

    @Schema(description = "已保存的打卡项")
    record Habit(@Schema(description = "十进制打卡项 ID") String id,
                 @Schema(description = "规范化名称", example = "阅读") String name,
                 @Schema(description = "UTC 创建时间") Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    Habits(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    Habit create(long userId, String name) {
        try {
            return transaction.execute(status -> {
                var keys = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var insert = connection.prepareStatement(
                            "INSERT INTO habits (user_id, name) VALUES (?, ?)", new String[]{"id"});
                    insert.setLong(1, userId);
                    insert.setString(2, name);
                    return insert;
                }, keys);
                Number id = keys.getKey();
                if (id == null) {
                    throw new AuthFailure(HABIT_DATABASE_UNAVAILABLE);
                }
                return jdbc.queryForObject("""
                        SELECT id, name, created_at FROM habits WHERE user_id = ? AND id = ?
                        """, ROW, userId, id.longValue());
            });
        } catch (DuplicateKeyException failure) {
            // [D7-A] 只有组合名称索引冲突才返回重名响应，见 docs/decisions(决策).md#D7。
            if (failure.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 1062
                    && NAME_CONSTRAINT.matcher(sql.getMessage()).find()) {
                throw new AuthFailure(HABIT_NAME_CONFLICT);
            }
            throw new AuthFailure(HABIT_DATABASE_UNAVAILABLE);
        } catch (DataAccessException | TransactionException ignored) {
            throw new AuthFailure(HABIT_DATABASE_UNAVAILABLE);
        }
    }

    List<Habit> list(long userId) {
        try {
            return jdbc.query("""
                    SELECT id, name, created_at FROM habits
                    WHERE user_id = ? ORDER BY created_at DESC, id DESC
                    """, ROW, userId);
        } catch (DataAccessException ignored) {
            throw new AuthFailure(HABIT_DATABASE_UNAVAILABLE);
        }
    }

    void requireOwned(long userId, long habitId) {
        try {
            boolean owned = Boolean.TRUE.equals(jdbc.query("SELECT id FROM habits WHERE user_id = ? AND id = ?",
                    rs -> { return rs.next(); }, userId, habitId));
            if (!owned) throw new AuthFailure(HABIT_NOT_FOUND);
        } catch (DataAccessException ignored) {
            throw new AuthFailure(CHECKIN_DATABASE_UNAVAILABLE);
        }
    }
}
