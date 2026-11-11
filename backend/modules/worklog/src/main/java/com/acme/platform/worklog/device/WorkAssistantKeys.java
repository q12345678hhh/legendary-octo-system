package com.acme.platform.worklog.device;

import com.acme.platform.platform.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 策略与更新清单的签名密钥。
 *
 * <p>设备私钥永远在员工机器上；这里只持有平台签发侧的密钥，并从环境变量注入。
 * 缺少私钥或公钥时相关端点返回 503，而不是签发一个「不签名」的策略。
 * 公私钥分开配置：Java 无法从 EC 私钥直接导出公钥，启动时生成密钥会让已固定公钥的客户端全部失效。
 */
@Component
public class WorkAssistantKeys {

    private static final String ALGORITHM = "EC";
    private static final String SIGNATURE = "SHA256withECDSAinP1363Format";

    private final WorkAssistantProperties properties;
    private PrivateKey policyKey;
    private PrivateKey updateKey;
    private boolean policyLoaded;
    private boolean updateLoaded;

    public WorkAssistantKeys(WorkAssistantProperties properties) {
        this.properties = properties;
    }

    public String policyKeyId() {
        return properties.getPolicyKeyId();
    }

    public String updateKeyId() {
        return properties.getUpdateKeyId();
    }

    public boolean policyConfigured() {
        return !properties.getPolicyPrivateKey().isBlank()
                && !properties.getPolicyPublicKeySpki().isBlank();
    }

    public boolean updateConfigured() {
        return !properties.getUpdatePrivateKey().isBlank()
                && !properties.getUpdatePublicKeySpki().isBlank();
    }

    public String policyPublicKeySpki() {
        requirePolicyConfigured();
        return properties.getPolicyPublicKeySpki();
    }

    public String updatePublicKeySpki() {
        requireUpdateConfigured();
        return properties.getUpdatePublicKeySpki();
    }

    public String signPolicy(String canonical) {
        requirePolicyConfigured();
        if (!policyLoaded) {
            policyKey = parsePrivate(properties.getPolicyPrivateKey(), "策略签名私钥");
            policyLoaded = true;
        }
        return sign(policyKey, canonical);
    }

    public String signUpdate(String canonical) {
        requireUpdateConfigured();
        if (!updateLoaded) {
            updateKey = parsePrivate(properties.getUpdatePrivateKey(), "更新清单签名私钥");
            updateLoaded = true;
        }
        return sign(updateKey, canonical);
    }

    private void requirePolicyConfigured() {
        if (!policyConfigured()) {
            throw new ApiException(503, "POLICY_SIGNING_UNAVAILABLE",
                    "平台未配置策略签名私钥或公钥");
        }
    }

    private void requireUpdateConfigured() {
        if (!updateConfigured()) {
            throw new ApiException(503, "UPDATE_SIGNING_UNAVAILABLE",
                    "平台未配置更新清单签名私钥或公钥");
        }
    }

    private static PrivateKey parsePrivate(String base64, String label) {
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(label + " 不是合法的 ECDSA PKCS8 base64", e);
        }
    }

    private static String sign(PrivateKey key, String canonical) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE);
            signature.initSign(key);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    /** 用 SPKI 公钥验证 P1363 签名；供设备请求验签与自检复用。 */
    public static boolean verify(String publicKeySpki, String canonical, String signatureBase64) {
        try {
            PublicKey key = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpki)));
            Signature verifier = Signature.getInstance(SIGNATURE);
            verifier.initVerify(key);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
