package com.uniappweb.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 习惯打卡 API 的启动入口，加载 Web、MySQL 数据源和 Redis 的自动配置。
 * 连接参数由运行环境提供。
 * @see "docs/decisions(决策).md#D2"
 * @see "docs/decisions(决策).md#D3"
 */
@SpringBootApplication
public class UniappWebApplication {

    /**
     * 启动 HTTP 应用。
     *
     * @param args Spring Boot 命令行参数
     */
    public static void main(String[] args) {
        var context = SpringApplication.run(UniappWebApplication.class, args);
        if (context.getEnvironment().matchesProfiles("demo-init")) {
            context.close();
        }
    }
}
