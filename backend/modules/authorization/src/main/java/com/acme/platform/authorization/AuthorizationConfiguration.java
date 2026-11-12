package com.acme.platform.authorization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 授权模块装配：配置绑定与两个可替换的安全扩展点。 */
@Configuration
@EnableConfigurationProperties(AuthorizationProperties.class)
public class AuthorizationConfiguration {

    /** WP02-04 默认放行；WP02-05 注入真实职责分离规则。 */
    @Bean
    @ConditionalOnMissingBean
    SeparationOfDutyChecker separationOfDutyChecker() {
        return (subject, permission) -> false;
    }

    /** WP02-04 默认放行；后续安全模块按设备/网络/风险等级拦截。 */
    @Bean
    @ConditionalOnMissingBean
    RiskGate riskGate() {
        return (subject, request) -> false;
    }
}
