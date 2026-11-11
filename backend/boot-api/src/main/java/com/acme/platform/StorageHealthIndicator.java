package com.acme.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * 对象存储健康指示器。
 *
 * <p>只探活存储服务本身（MinIO 的 {@code /minio/health/live}），不校验桶与权限：
 * 桶是否可写由上传链路验收，混在一处会让「存储挂了」和「桶配错了」难以区分。
 */
@Component("storage")
class StorageHealthIndicator extends AbstractHealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final URI healthUri;
    private final HttpClient httpClient;

    StorageHealthIndicator(@Value("${platform.storage.health-url}") URI healthUri) {
        super("对象存储探活失败");
        this.healthUri = healthUri;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(healthUri).timeout(TIMEOUT).GET().build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) {
            builder.up().withDetail("endpoint", healthUri.getAuthority());
        } else {
            builder.down()
                    .withDetail("endpoint", healthUri.getAuthority())
                    .withDetail("status", response.statusCode());
        }
    }
}
