package com.acme.platform.tenancy;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * tenancy 模块装配：只登记时钟，数据源与事务模板由 Boot 自动配置提供。
 */
@Configuration
public class TenancyConfiguration {

    /** 冷静期与支持访问时限都以注入的时钟为准，测试可替换为固定时钟。 */
    @Bean
    public Clock tenancyClock() {
        return Clock.systemUTC();
    }
}
