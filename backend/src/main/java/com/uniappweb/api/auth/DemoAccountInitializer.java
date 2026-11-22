package com.uniappweb.api.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 仅在显式 demo-init 模式下，从环境读取演示密码并在 MySQL 事务内幂等初始化甲乙。
 *
 * @see "docs/decisions(决策).md#D4"
 */
@Component
@Profile("demo-init")
final class DemoAccountInitializer implements ApplicationRunner {

    // 演示密码需要单独通过本地配置提供，最少 15 个 Unicode 码点。
    private static final int MIN_DEMO_PASSWORD_LENGTH = 15;
    private final Environment environment;
    private final AuthUsers users;
    private final AuthPasswords passwords;
    private final TransactionTemplate transaction;

    DemoAccountInitializer(Environment environment, AuthUsers users, AuthPasswords passwords,
                           TransactionTemplate transaction) {
        this.environment = environment;
        this.users = users;
        this.passwords = passwords;
        this.transaction = transaction;
    }

    /**
     * 校验两项配置后在同一事务中初始化账号。
     *
     * @param args 初始化进程的启动参数
     * @throws IllegalArgumentException 演示密码缺失、仍是占位值、相同或长度不符
     * @see "docs/decisions(决策).md#D4"
     */
    @Override
    public void run(ApplicationArguments args) {
        String a = environment.getProperty("DEMO_A_PASSWORD");
        String b = environment.getProperty("DEMO_B_PASSWORD");
        if (!AuthPasswords.validLength(a, MIN_DEMO_PASSWORD_LENGTH)
                || !AuthPasswords.validLength(b, MIN_DEMO_PASSWORD_LENGTH)
                || a.equals(b) || a.equals("replace_with_demo_a_password")
                || b.equals("replace_with_demo_b_password")) {
            throw new IllegalArgumentException(
                    "DEMO_A_PASSWORD and DEMO_B_PASSWORD must be different and contain 15–128 characters.");
        }
        String hashA = passwords.encode(a);
        String hashB = passwords.encode(b);
        transaction.executeWithoutResult(ignored -> {
            users.insertDemo("demo_a", "甲", hashA);
            users.insertDemo("demo_b", "乙", hashB);
        });
    }
}
