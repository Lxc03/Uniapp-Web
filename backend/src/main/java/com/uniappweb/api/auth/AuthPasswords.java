package com.uniappweb.api.auth;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 归一化用户名并用 Argon2id 编码和校验密码，仅由认证与演示账号初始化使用。
 *
 * @see "docs/decisions(决策).md#D4"
 */
@Component
final class AuthPasswords {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,31}");
    private static final int MAX_PASSWORD_LENGTH = 128;

    // [D4-A] 19 MiB、两次迭代、单路并行，盐 16 字节、摘要 32 字节，见 docs/decisions(决策).md#D4。
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    private final String dummyHash = encode(UUID.randomUUID().toString());

    static String normalizeUsername(String username) {
        return username != null && USERNAME.matcher(username).matches()
                ? username.toLowerCase(Locale.ROOT) : null;
    }

    static boolean validLength(String password, int minimum) {
        if (password == null || password.length() > MAX_PASSWORD_LENGTH * 2) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        return length >= minimum && length <= MAX_PASSWORD_LENGTH
                && password.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE
                                                        && cp <= Character.MAX_SURROGATE);
    }

    String encode(String password) {
        return encoder.encode(password);
    }

    boolean matches(String password, String hash) {
        return encoder.matches(password, hash == null ? dummyHash : hash);
    }
}
