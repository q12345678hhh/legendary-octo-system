package com.acme.platform.worklog.device;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 工作助手装配：只登记配置属性，密钥对象由 {@link WorkAssistantKeys} 按需解析。 */
@Configuration
@EnableConfigurationProperties(WorkAssistantProperties.class)
public class WorkAssistantConfiguration {
}
