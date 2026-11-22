package com.uniappweb.acceptance;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniappweb.api.UniappWebApplication;
import com.uniappweb.api.habits.P42SnapshotFixture;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 隔离验收入口，复用 P3 的业务时钟与事务屏障，允许针对单项业务缓存或会话读取注入故障。
 *
 * @see "docs/decisions(决策).md#D14"
 */
@Configuration(proxyBeanMethods = false)
public class P42TestApplication {

    private static final Path CONTROL = Path.of("/tmp/p42-control");
    private static final Duration HOLD_LIMIT = Duration.ofSeconds(50);

    /**
     * 启动隔离验收入口。
     *
     * @param args 隔离 Spring Boot 进程的启动参数
     */
    public static void main(String[] args) {
        var context = new SpringApplicationBuilder(UniappWebApplication.class,
                P32TestApplication.class, P42TestApplication.class).run(args);
        if (context.getEnvironment().matchesProfiles("demo-init")) context.close();
    }

    @Bean
    @Primary
    P42SnapshotFixture snapshotFixture(StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        return new P42SnapshotFixture(redis, mapper, clock);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory) {
            /**
             * 使用真实 Redis，只有指定的会话 GET 触发超时。
             *
             * @return 测试专用值操作代理
             */
            @Override
            @SuppressWarnings("unchecked")
            public ValueOperations<String, String> opsForValue() {
                var operations = super.opsForValue();
                return (ValueOperations<String, String>) Proxy.newProxyInstance(
                        ValueOperations.class.getClassLoader(), new Class<?>[]{ValueOperations.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("get") && args[0] instanceof String key
                                    && key.startsWith("auth:session:v1:") && selected("fail-session", "on")) {
                                throw new QueryTimeoutException("Isolated session test fault");
                            }
                            try {
                                return method.invoke(operations, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
            }
        };
    }

    /**
     * 查验测试选择文件是否为指定目标。
     *
     * @param name 受控文件名
     * @param target 期望的目标项或开关值
     * @return 文件存在且内容匹配时为 true
     * @throws IllegalStateException 无法读取控制文件
     */
    public static boolean selected(String name, String target) {
        Path file = CONTROL.resolve(name);
        try {
            return Files.exists(file) && Files.readString(file).strip().equals(target);
        } catch (java.nio.file.NoSuchFileException ignored) {
            return false;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read acceptance control", failure);
        }
    }

    /**
     * 将实际测量值追加到隔离测试事件文件。
     *
     * @param name 测试事件文件名
     * @param event 脱敏测量值
     * @throws IllegalStateException 无法记录事件
     */
    public static void note(String name, String event) {
        try {
            Files.writeString(CONTROL.resolve(name), event + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot record acceptance event", failure);
        }
    }

    /**
     * 消费目标项的单次暂停开关，在查库之后、回填之前等待释放。
     *
     * @param key 当前尝试回填的测试项缓存键
     * @param expiresAt 查库前确定的 Redis 截止时间
     * @return 本次是否执行过暂停
     * @throws IllegalStateException 测试屏障失败或未按时释放
     */
    public static boolean holdFill(String key, long expiresAt) {
        try {
            if (!selected("arm-fill", key) || !Files.deleteIfExists(CONTROL.resolve("arm-fill"))) return false;
            note("fill-held", Long.toString(expiresAt));
            long timeout = System.nanoTime() + HOLD_LIMIT.toNanos();
            while (!Files.deleteIfExists(CONTROL.resolve("release-fill"))) {
                if (System.nanoTime() > timeout) throw new IllegalStateException("Fill barrier timed out");
                Thread.sleep(10);
            }
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException("Fill barrier failed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fill barrier interrupted", failure);
        }
    }
}
