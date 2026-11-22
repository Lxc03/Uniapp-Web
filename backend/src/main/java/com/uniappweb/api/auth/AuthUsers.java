package com.uniappweb.api.auth;

import java.util.Optional;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 使用 JdbcTemplate 访问用户表，支持凭据校验、会话恢复和幂等演示账号初始化。
 *
 * @see "docs/decisions(决策).md#D4"
 * @see "docs/decisions(决策).md#D5"
 */
@Repository
class AuthUsers {

    @Schema(description = "当前用户的公开资料")
    record User(@Schema(description = "十进制用户 ID", example = "1") String id,
                @Schema(description = "规范化用户名", example = "demo_a") String username,
                @Schema(description = "展示名称", example = "甲") String displayName) {
    }

    record Account(User user, String passwordHash) {
    }

    private final JdbcTemplate jdbc;

    AuthUsers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Account> findByUsername(String username) {
        try {
            return jdbc.query("""
                    SELECT id, username, display_name, password_hash
                    FROM users WHERE username = ?
                    """, rs -> rs.next() ? Optional.of(
                    new Account(new User(rs.getString("id"), rs.getString("username"),
                            rs.getString("display_name")), rs.getString("password_hash")))
                    : Optional.empty(), username);
        } catch (DataAccessException ignored) {
            throw new AuthFailure(AuthFailure.Kind.AUTH_DATABASE_UNAVAILABLE);
        }
    }

    Optional<User> findById(long id) {
        try {
            return jdbc.query("""
                    SELECT id, username, display_name FROM users WHERE id = ?
                    """, rs -> rs.next() ? Optional.of(new User(rs.getString("id"),
                    rs.getString("username"), rs.getString("display_name")))
                    : Optional.empty(), id);
        } catch (DataAccessException ignored) {
            throw new AuthFailure(AuthFailure.Kind.AUTH_DATABASE_UNAVAILABLE);
        }
    }

    void insertDemo(String username, String displayName, String hash) {
        // [D4-A] 唯一索引裁决并发初始化，重复时保留 ID 与原摘要，见 docs/decisions(决策).md#D4。
        jdbc.update("""
                INSERT INTO users (username, display_name, password_hash)
                VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE id = id
                """, AuthPasswords.normalizeUsername(username), displayName, hash);
    }
}
