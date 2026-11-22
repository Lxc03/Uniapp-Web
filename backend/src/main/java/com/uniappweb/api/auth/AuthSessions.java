package com.uniappweb.api.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis 存放固定期限的会话，只以 Cookie 值的 SHA-256 摘要做键。
 * 首次领取写令牌时在现有会话键内原子补齐。
 *
 * @see "docs/decisions(决策).md#D5"
 * @see "docs/decisions(决策).md#D8"
 */
@Component
final class AuthSessions {

    private static final String KEY_PREFIX = "auth:session:v1:";
    // [D5-A] 登录签发后固定有效 30 分钟，读取会话不会续期，见 docs/decisions(决策).md#D5。
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final int RANDOM_CREDENTIAL_BYTES = 32;
    private static final int MAX_ALLOCATION_ATTEMPTS = 3;
    // 32 字节凭据采用无填充的 Base64 URL 编码，对应 43 个字符。
    private static final Pattern CREDENTIAL = Pattern.compile("[A-Za-z0-9_-]{43}");
    // [D8-A] 检查 PTTL 并以 XX KEEPTTL 原位写入，兼容旧会话且保留过期点，见 docs/decisions(决策).md#D8。
    private static final DefaultRedisScript<String> CLAIM_CSRF = new DefaultRedisScript<>("""
            local payload = redis.call('GET', KEYS[1])
            if not payload or redis.call('PTTL', KEYS[1]) <= 0 then
                return nil
            end

            local session = cjson.decode(payload)
            if session.userId ~= ARGV[1] then
                return nil
            end
            if session.csrfToken then
                return session.csrfToken
            end

            session.csrfToken = ARGV[2]
            if redis.call('SET', KEYS[1], cjson.encode(session), 'XX', 'KEEPTTL') then
                return session.csrfToken
            end
            return nil
            """, String.class);

    record Session(long userId, String csrfToken) {
    }

    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    AuthSessions(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    String create(String userId) {
        String payload = mapper.createObjectNode().put("userId", userId).toString();
        try {
            for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
                String sid = randomCredential();
                // [D5-A] 用同一次 SET 保存会话和 TTL，成功后才签发 Cookie，见 docs/decisions(决策).md#D5。
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(sid), payload, LIFETIME))) {
                    return sid;
                }
            }
        } catch (DataAccessException ignored) {
            throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
        }
        throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
    }

    Optional<Session> find(String sid) {
        if (!CREDENTIAL.matcher(sid).matches()) {
            return Optional.empty();
        }
        try {
            // GET 的空结果代表失效，Redis 读异常代表服务故障。GET 不延长 TTL。
            String payload = redis.opsForValue().get(key(sid));
            if (payload == null) {
                return Optional.empty();
            }
            var session = mapper.readTree(payload);
            if (session == null || !session.path("userId").isTextual()) {
                throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
            }
            long userId = Long.parseLong(session.path("userId").textValue());
            if (userId <= 0) {
                throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
            }
            var csrf = session.path("csrfToken");
            if (!csrf.isMissingNode() && (!csrf.isTextual() || !CREDENTIAL.matcher(csrf.textValue()).matches())) {
                throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
            }
            return Optional.of(new Session(userId, csrf.textValue()));
        } catch (DataAccessException | IOException | NumberFormatException ignored) {
            throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
        }
    }

    String claimCsrf(String sid, String userId) {
        try {
            // 单键脚本在 Redis 内确认仍有效的会话，读取已有令牌或原地补齐。
            String token = redis.execute(CLAIM_CSRF, List.of(key(sid)), userId, randomCredential());
            if (token == null) {
                throw new AuthFailure(AuthFailure.Kind.AUTH_SESSION_INVALID_OR_EXPIRED);
            }
            if (!CREDENTIAL.matcher(token).matches()) {
                throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
            }
            return token;
        } catch (DataAccessException ignored) {
            throw new AuthFailure(AuthFailure.Kind.AUTH_REDIS_UNAVAILABLE);
        }
    }

    private String randomCredential() {
        byte[] bytes = new byte[RANDOM_CREDENTIAL_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String key(String sid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sid.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
