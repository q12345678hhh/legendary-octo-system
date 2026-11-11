package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/**
 * 用 JDK 自带 HttpServer 起一个假存储端，验证 UP/DOWN 判定，
 * 避免为了一个探活断言去拉 Testcontainers 或真实 MinIO。
 */
class StorageHealthIndicatorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsUpWhenStorageAnswers200() throws Exception {
        StorageHealthIndicator indicator = indicatorReturning(200);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenStorageAnswersNon200() throws Exception {
        StorageHealthIndicator indicator = indicatorReturning(503);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private StorageHealthIndicator indicatorReturning(int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/minio/health/live", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return new StorageHealthIndicator(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/minio/health/live"));
    }
}
