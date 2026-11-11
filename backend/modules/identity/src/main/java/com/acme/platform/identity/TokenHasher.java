package com.acme.platform.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 不透明令牌的生成与哈希。
 *
 * <p>数据库只保存 SHA-256 哈希：即使库被读走，也无法还原会话或 API 令牌。
 * 令牌本身是 32 字节随机数，不做任何可预测编码。
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private TokenHasher() {
    }

    public record IssuedToken(String raw, String hash, String prefix) {
    }

    public static IssuedToken issue(String kind) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = "plt_" + kind + "_" + ENCODER.encodeToString(bytes);
        return new IssuedToken(raw, hash(raw), kind + "_" + raw.substring(4, Math.min(12, raw.length())));
    }

    public static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
