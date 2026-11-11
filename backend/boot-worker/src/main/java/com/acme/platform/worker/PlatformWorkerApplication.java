package com.acme.platform.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker 进程装配入口：异步作业、通知、同步、报表与 AI 作业。
 *
 * <p>调度器负责轮询 outbox，因此需要 {@code @EnableScheduling}；
 * {@code @ConfigurationPropertiesScan} 用来绑定 {@code platform.outbox.*}。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PlatformWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformWorkerApplication.class, args);
    }
}
