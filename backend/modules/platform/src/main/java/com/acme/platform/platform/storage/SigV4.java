package com.acme.platform.platform.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SigV4 需要的最小工具：RFC 3986 编码、SHA-256、HMAC-SHA256。
 *
 * <p>单独抽出来是为了让编码规则可被单元测试钉死：S3 的编码比 URLEncoder 严格，
 * 空格必须是 %20、波浪号不能编码、斜杠是否编码取决于位置。
 */
final class SigV4 {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    /** 空载荷的 SHA-256，用于 HEAD/DELETE 这类没有正文的请求。 */
    static final String EMPTY_PAYLOAD_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private SigV4() {
    }

    static String uriEncode(String value, boolean encodeSlash) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved || (c == '/' && !encodeSlash)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    static byte[] hmacSha256(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 计算失败", e);
        }
    }

    static byte[] signingKey(String secretKey, String dateStamp, String region) {
        byte[] dateKey = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] regionKey = hmacSha256(dateKey, region);
        byte[] serviceKey = hmacSha256(regionKey, "s3");
        return hmacSha256(serviceKey, "aws4_request");
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
