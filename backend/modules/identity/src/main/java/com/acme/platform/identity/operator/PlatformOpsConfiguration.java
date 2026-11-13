package com.acme.platform.identity.operator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 平台运营端属性装配。 */
@Configuration
@EnableConfigurationProperties(PlatformOpsProperties.class)
public class PlatformOpsConfiguration {
}
