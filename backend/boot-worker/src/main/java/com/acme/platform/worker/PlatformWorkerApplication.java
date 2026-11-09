package com.acme.platform.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Worker 进程装配入口：异步作业、通知、同步、报表与 AI 作业。
 */
@SpringBootApplication
public class PlatformWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformWorkerApplication.class, args);
    }
}
