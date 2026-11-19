package com.acme.platform.platform.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 对象存储的最小读写面：存在性检查、删除，以及一次性的受限下载。
 *
 * <p>HEAD/DELETE 使用请求头签名（不是查询签名），因为这两个动作由服务端发起，
 * 不经过浏览器；URI 里不带凭证，日志可以安全记录对象键。
 *
 * <p>{@link #download(String, long)} 只服务于「读一次就处理完」的场景（语音转写），
 * 必须带硬上限，调用方不得把字节缓存到进程内共享结构或日志。
 */
@Component
public class ObjectStorageClient {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final ObjectStorageProperties properties;
    private final HttpClient httpClient;

    public ObjectStorageClient(ObjectStorageProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public boolean exists(String objectKey) {
        HttpRequest request = signedRequest("HEAD", objectKey);
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("对象存储不可达：" + properties.baseUrl(), e);
        }
        if (response.statusCode() == 200) {
            return true;
        }
        if (response.statusCode() == 404) {
            return false;
        }
        throw new IllegalStateException("对象存储 HEAD 返回 " + response.statusCode());
    }

    public void delete(String objectKey) {
        HttpRequest request = signedRequest("DELETE", objectKey);
        try {
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 204 && status != 200 && status != 404) {
                throw new IllegalStateException("对象存储 DELETE 返回 " + status);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("对象存储不可达：" + properties.baseUrl(), e);
        }
    }

    /**
     * 受限下载：最多读取 {@code maxBytes}，超过即失败。
     *
     * <p>只用于「读一次就转写」的音频，调用方不得保存或记录内容。
     */
    public byte[] download(String objectKey, long maxBytes) {
        HttpRequest request = signedRequest("GET", objectKey, Duration.ofSeconds(30));
        HttpResponse<java.io.InputStream> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("对象存储不可达：" + properties.baseUrl(), e);
        }
        if (response.statusCode() != 200) {
            try {
                response.body().close();
            } catch (java.io.IOException ignored) {
                // 关闭失败不影响错误语义
            }
            throw new IllegalStateException("对象存储 GET 返回 " + response.statusCode());
        }
        try (java.io.InputStream in = response.body();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("对象超过允许的下载上限");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取对象存储失败", e);
        }
    }

    private HttpRequest signedRequest(String method, String objectKey) {
        return signedRequest(method, objectKey, Duration.ofSeconds(5));
    }

    private HttpRequest signedRequest(String method, String objectKey, Duration timeout) {
        properties.requireCredentials();
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String host = properties.host();
        String canonicalUri = "/" + properties.bucket() + "/" + SigV4.uriEncode(objectKey, false);
        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + SigV4.EMPTY_PAYLOAD_HASH + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + SigV4.EMPTY_PAYLOAD_HASH;
        String scope = dateStamp + "/" + properties.region() + "/s3/aws4_request";
        String stringToSign = SigV4.ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + SigV4.sha256Hex(canonicalRequest);
        byte[] signingKey = SigV4.signingKey(properties.secretKey(), dateStamp, properties.region());
        String signature = SigV4.hex(SigV4.hmacSha256(signingKey, stringToSign));
        String authorization = SigV4.ALGORITHM + " Credential=" + properties.accessKey() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        return HttpRequest.newBuilder(URI.create(properties.baseUrl() + canonicalUri))
                .timeout(timeout)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", SigV4.EMPTY_PAYLOAD_HASH)
                .header("Authorization", authorization)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }
}
