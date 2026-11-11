package com.acme.platform.organization;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 组织模块装配：只绑定配置，存储与事务由 Boot 自动配置提供。 */
@Configuration
@EnableConfigurationProperties(OrganizationProperties.class)
public class OrganizationConfiguration {
}
