package com.acme.platform.identity;

import com.acme.platform.platform.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * TOTP 密钥的静态加密：AES-256-GCM，随机 12 字节 nonce。
 *
 * <p>密钥只来自 {@code platform.identity.mfa-key}（base64 32 字节）。缺失或格式错误时
 * 拒绝注册 MFA（503），不会退化为明文存储。
 */
@Component
public class TotpSecretCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;

    private final IdentityProperties properties;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCipher(IdentityProperties properties) {
        this.properties = properties;
    }

    public record Encrypted(String ciphertext, String nonce) {
    }

    public Encrypted encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA 密钥加密失败", e);
        }
    }

    public String decrypt(String ciphertext, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(nonce)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("MFA 密钥解密失败", e);
        }
    }

    private SecretKeySpec key() {
        String configured = properties.getMfaKey();
        if (configured == null || configured.isBlank()) {
            throw new ApiException(503, "MFA_UNAVAILABLE", "未配置 MFA 加密密钥，暂不能注册 MFA");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            throw new ApiException(503, "MFA_UNAVAILABLE", "MFA 加密密钥格式不正确");
        }
        if (decoded.length != KEY_BYTES) {
            throw new ApiException(503, "MFA_UNAVAILABLE", "MFA 加密密钥必须是 32 字节");
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
