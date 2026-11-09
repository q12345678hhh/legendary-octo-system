package com.acme.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * API 进程装配入口。只做装配：业务能力全部来自 {@code com.acme.platform.<module>} 下的模块。
 */
@Modulithic(systemName = "Enterprise Application Platform")
@SpringBootApplication
public class PlatformApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApiApplication.class, args);
    }
}
