package com.acme.platform.platform.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置。密钥只从环境变量注入，源码里的默认值仅用于本地 compose。
 *
 * @param endpoint   S3 兼容端点，例如 http://127.0.0.1:9000
 * @param bucket     存储桶，开发、测试、生产必须使用不同桶
 * @param region     签名区域，MinIO 默认 us-east-1
 * @param accessKey  访问密钥
 * @param secretKey  密钥
 * @param uploadTtl  上传预签名有效期
 * @param downloadTtl 下载预签名有效期（越短越好）
 */
@ConfigurationProperties(prefix = "platform.storage")
public record ObjectStorageProperties(
        String endpoint,
        String bucket,
        String region,
        String accessKey,
        String secretKey,
        Duration uploadTtl,
        Duration downloadTtl) {

    public ObjectStorageProperties {
        endpoint = endpoint == null || endpoint.isBlank() ? "http://localhost:9000" : endpoint.strip();
        bucket = bucket == null || bucket.isBlank() ? "platform-dev" : bucket.strip();
        region = region == null || region.isBlank() ? "us-east-1" : region.strip();
        uploadTtl = uploadTtl == null ? Duration.ofMinutes(15) : uploadTtl;
        downloadTtl = downloadTtl == null ? Duration.ofMinutes(5) : downloadTtl;
    }

    public String baseUrl() {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    public String host() {
        return java.net.URI.create(baseUrl()).getRawAuthority();
    }

    public void requireCredentials() {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "对象存储凭据缺失：请设置 PLATFORM_STORAGE_ACCESS_KEY / PLATFORM_STORAGE_SECRET_KEY");
        }
    }
}
