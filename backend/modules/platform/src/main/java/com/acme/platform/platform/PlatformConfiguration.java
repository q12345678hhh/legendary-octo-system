package com.acme.platform.platform;

import com.acme.platform.platform.storage.ObjectStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台模块装配：只登记配置属性，不在这里创建数据源或事务管理器。
 *
 * <p>数据源、事务模板由 Boot 自动配置提供；本模块在 API 与 Worker 里都复用同一套，
 * 保证租户上下文的行为一致。
 */
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class PlatformConfiguration {
}
