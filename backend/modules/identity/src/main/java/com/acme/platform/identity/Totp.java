package com.acme.platform.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP（30 秒步长、6 位、HmacSHA1），无第三方依赖。
 *
 * <p>校验窗口为当前步 ±1；同一时间步只能使用一次，调用方通过 {@code lastUsedStep} 拒绝重放。
 */
public final class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static String currentCode(String secret, long epochSecond) {
        return codeForStep(secret, epochSecond / STEP_SECONDS);
    }

    public static long verify(String secret, String code, long epochSecond, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long current = epochSecond / STEP_SECONDS;
        for (long step = current - 1; step <= current + 1; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue;
            }
            if (codeForStep(secret, step).equals(code)) {
                return step;
            }
        }
        return -1;
    }

    public static String otpauthUri(String issuer, String account, String secret) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + secret + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    static String codeForStep(String secret, long step) {
        try {
            byte[] key = base32Decode(secret);
            byte[] data = new byte[8];
            long value = step;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (value & 0xFF);
                value >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                out.append(ALPHABET.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] base32Decode(String value) {
        String normalized = value.trim().replace("=", "").toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[normalized.length() * 5 / 8];
        int index = 0;
        for (char ch : normalized.toCharArray()) {
            int value2 = ALPHABET.indexOf(ch);
            if (value2 < 0) {
                throw new IllegalArgumentException("非法 Base32 字符：" + ch);
            }
            buffer = (buffer << 5) | value2;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[index++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out;
    }
}
