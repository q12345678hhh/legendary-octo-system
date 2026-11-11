package com.acme.platform.platform.storage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * S3 兼容对象存储的 SigV4 查询签名。
 *
 * <p>不引第三方 SDK 是刻意选择：预签名只依赖哈希与 HMAC，自己实现可以把
 * 「哪些头参与签名、有效期多长、路径怎么编码」写成可读代码并单测覆盖，
 * 避免为一个函数引入整条云厂商 SDK 供应链。
 *
 * <p>签名只覆盖 host：PUT 的 Content-Type 由调用方自由设置，不参与签名；
 * 这也意味着预签名 URL 是 bearer 凭证，跨主体防护必须靠签发前再授权与短 TTL。
 */
@Component
public class S3Presigner {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final long MAX_EXPIRES_SECONDS = 604800L;

    private final ObjectStorageProperties properties;

    public S3Presigner(ObjectStorageProperties properties) {
        this.properties = properties;
    }

    public String presignPut(String objectKey, Duration ttl) {
        return presign("PUT", objectKey, ttl);
    }

    public String presignGet(String objectKey, Duration ttl) {
        return presign("GET", objectKey, ttl);
    }

    String presign(String method, String objectKey, Duration ttl) {
        properties.requireCredentials();
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        long expires = Math.max(1, Math.min(MAX_EXPIRES_SECONDS, ttl.toSeconds()));
        String scope = dateStamp + "/" + properties.region() + "/s3/aws4_request";
        String canonicalUri = "/" + properties.bucket() + "/" + SigV4.uriEncode(objectKey, false);

        Map<String, String> query = new TreeMap<>();
        query.put("X-Amz-Algorithm", SigV4.ALGORITHM);
        query.put("X-Amz-Credential", properties.accessKey() + "/" + scope);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", Long.toString(expires));
        query.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = query.entrySet().stream()
                .map(entry -> SigV4.uriEncode(entry.getKey(), true) + "="
                        + SigV4.uriEncode(entry.getValue(), true))
                .collect(Collectors.joining("&"));

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + "host:" + properties.host() + "\n"
                + "\n"
                + "host\n"
                + "UNSIGNED-PAYLOAD";

        String stringToSign = SigV4.ALGORITHM + "\n"
                + amzDate + "\n"
                + scope + "\n"
                + SigV4.sha256Hex(canonicalRequest);

        byte[] signingKey = SigV4.signingKey(properties.secretKey(), dateStamp, properties.region());
        String signature = SigV4.hex(SigV4.hmacSha256(signingKey, stringToSign));

        return properties.baseUrl() + canonicalUri + "?" + canonicalQuery
                + "&X-Amz-Signature=" + signature;
    }
}
