package com.uniappweb.api.habits;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供业务时区和可注入时钟。控制器负责在每次请求入口采样，测试可注入独立时钟。
 *
 * @see "docs/decisions(决策).md#D9"
 */
@Configuration(proxyBeanMethods = false)
class BusinessTime {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    Clock businessClock() {
        return Clock.systemUTC();
    }
}
