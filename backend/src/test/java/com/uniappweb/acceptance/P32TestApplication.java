package com.uniappweb.acceptance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.uniappweb.api.UniappWebApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

/**
 * P3.2 隔离验收入口。向 Spring 注入文件时钟和 JdbcTemplate 提交屏障，仅编入测试类路径。
 *
 * @see "docs/decisions(决策).md#D9"
 * @see "docs/decisions(决策).md#D10"
 */
@Configuration(proxyBeanMethods = false)
public class P32TestApplication {

    private static final Path CONTROL = Path.of("/tmp/p32-control");

    /**
     * 启动隔离 API，显式初始化演示账号时在初始化结束后退出。
     *
     * @param args 隔离测试进程的 Spring Boot 启动参数
     */
    public static void main(String[] args) {
        var context = new SpringApplicationBuilder(UniappWebApplication.class, P32TestApplication.class).run(args);
        if (context.getEnvironment().matchesProfiles("demo-init")) context.close();
    }

    @Bean
    @Primary
    Clock acceptanceClock() {
        return new FileClock(ZoneOffset.UTC);
    }

    /** 通过隔离容器中的文件控制业务时点，可推进到午夜前后。 */
    private static final class FileClock extends Clock {
        private final ZoneId zone;

        FileClock(ZoneId zone) {
            this.zone = zone;
        }

        /** @return 当前时钟用于转换时点的时区 */
        @Override
        public ZoneId getZone() {
            return zone;
        }

        /**
         * 为相同的文件时点指定转换时区。
         *
         * @param zone 新的转换时区
         * @return 读取同一文件的时钟
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return new FileClock(zone);
        }

        /**
         * 从验收控制文件读取业务采样时刻。
         *
         * @return 测试用 UTC 时点
         * @throws IllegalStateException 未能读取验收时钟文件
         */
        @Override
        public Instant instant() {
            try {
                return Instant.parse(Files.readString(CONTROL.resolve("instant")).strip());
            } catch (IOException failure) {
                throw new IllegalStateException("Write the test instant before calling the habits API", failure);
            }
        }
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource) {
            /**
             * 执行 SQL，启用屏障时将打卡 INSERT 暂停在提交之前。
             *
             * @param sql 待执行的参数化 SQL
             * @param args JDBC 绑定值
             * @return 受影响的行数
             * @throws IllegalStateException 屏障未能在事务内完成
             */
            @Override
            public int update(String sql, Object... args) {
                int rows = super.update(sql, args);
                if (sql.startsWith("INSERT INTO habit_check_ins ")) {
                    traceCommit(args[0]);
                    holdBeforeCommit();
                }
                return rows;
            }
        };
    }

    private static void traceCommit(Object habitId) {
        if (!P42TestApplication.selected("track-commit", habitId.toString())) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 记录实际发出提交前的单调时钟。
             *
             * @param readOnly 当前事务是否标记为只读，打卡写事务为 false
             */
            @Override
            public void beforeCommit(boolean readOnly) {
                P42TestApplication.note("commit-before", Long.toString(System.nanoTime()));
            }

            /** 记录数据库事务提交成功后的单调时钟。 */
            @Override
            public void afterCommit() {
                P42TestApplication.note("commit-after", Long.toString(System.nanoTime()));
            }
        });
    }

    private static void holdBeforeCommit() {
        try {
            if (!Files.deleteIfExists(CONTROL.resolve("arm"))) return;
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("The check-in row must still be in a write transaction");
            }
            Files.createFile(CONTROL.resolve("inserted"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!Files.deleteIfExists(CONTROL.resolve("release"))) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Write barrier timed out");
                Thread.sleep(10);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Check-in test barrier failed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Check-in test barrier interrupted", failure);
        }
    }
}
