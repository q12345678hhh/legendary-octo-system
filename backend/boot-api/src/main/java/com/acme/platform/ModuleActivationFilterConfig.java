package com.acme.platform;

import com.acme.platform.appcatalog.ModuleActivationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 显式注册模块停用过滤器（原因见 {@link ModuleActivationFilter} 类注释）。 */
@Configuration
class ModuleActivationFilterConfig {

    @Bean
    ModuleActivationFilter moduleActivationFilter(ModuleActivationService activationService,
                                                  @Value("${platform.module.activation.enforce:true}")
                                                  boolean enforce) {
        return new ModuleActivationFilter(activationService, enforce);
    }
}
