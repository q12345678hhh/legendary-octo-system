package com.acme.platform.identity;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TOTP 注册、确认、登录校验与关闭。
 *
 * <p>密文只存数据库；明文密钥与恢复码只在注册/确认的响应里出现一次。
 */
@Service
public class MfaService {

    private static final String ISSUER = "Acme Platform";

    private final TenantTransactions tenantTransactions;
    private final MfaStore mfaStore;
    private final UserAccountStore userAccountStore;
    private final TotpSecretCipher cipher;
    private final Clock clock;

    public MfaService(TenantTransactions tenantTransactions,
                      MfaStore mfaStore,
                      UserAccountStore userAccountStore,
                      TotpSecretCipher cipher,
                      Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.mfaStore = mfaStore;
        this.userAccountStore = userAccountStore;
        this.cipher = cipher;
        this.clock = clock;
    }

    public record Enrollment(String secret, String otpauthUri) {
    }

    public record MfaStatusView(boolean enabled, int remainingRecoveryCodes) {
    }

    public Enrollment start(UUID userId, String email) {
        return tenantTransactions.inTenant(ctx -> {
            if (mfaStore.findActive(userId).isPresent()) {
                throw ApiException.conflict("MFA_ALREADY_ENABLED", "已启用 MFA，如需更换请先关闭");
            }
            String secret = Totp.generateSecret();
            TotpSecretCipher.Encrypted encrypted = cipher.encrypt(secret);
            mfaStore.disablePending(userId);
            mfaStore.insertPending(UUID.randomUUID(), userId, encrypted.ciphertext(), encrypted.nonce());
            return new Enrollment(secret, Totp.otpauthUri(ISSUER, email, secret));
        });
    }

    public List<String> confirm(UUID userId, String code) {
        return tenantTransactions.inTenant(ctx -> {
            MfaMethod pending = mfaStore.findPending(userId).orElseThrow(() ->
                    ApiException.conflict("MFA_NOT_STARTED", "请先开始 MFA 注册"));
            String secret = cipher.decrypt(pending.secretCiphertext(), pending.secretNonce());
            long step = Totp.verify(secret, code, clock.instant().getEpochSecond(), null);
            if (step < 0) {
                throw new ApiException(401, "MFA_CODE_INVALID", "验证码不正确");
            }
            mfaStore.activate(pending.id());
            mfaStore.updateLastStep(pending.id(), step);
            mfaStore.deleteRecoveryCodes(userId);
            List<String> codes = RecoveryCodes.generate();
            for (String value : codes) {
                mfaStore.insertRecoveryCode(userId, TokenHasher.hash(RecoveryCodes.normalize(value)));
            }
            return codes;
        });
    }

    public boolean verifyLogin(UUID userId, String code) {
        return tenantTransactions.inTenant(ctx -> {
            MfaMethod method = mfaStore.findActive(userId).orElse(null);
            if (method == null) {
                return false;
            }
            String secret = cipher.decrypt(method.secretCiphertext(), method.secretNonce());
            long step = Totp.verify(secret, code, clock.instant().getEpochSecond(),
                    method.lastUsedStep());
            if (step >= 0) {
                mfaStore.updateLastStep(method.id(), step);
                return true;
            }
            String normalized = RecoveryCodes.normalize(code);
            return normalized != null && !normalized.isBlank()
                    && mfaStore.consumeRecoveryCode(userId, TokenHasher.hash(normalized)) == 1;
        });
    }

    public void disable(UUID userId) {
        tenantTransactions.inTenant(ctx -> {
            UserAccount account = userAccountStore.findAccount(userId).orElseThrow(() ->
                    ApiException.notFound("USER_NOT_FOUND", "账号不存在"));
            if (account.mfaRequired()) {
                throw ApiException.conflict("MFA_REQUIRED_FOR_PRIVILEGED",
                        "高权限成员必须保持 MFA 开启");
            }
            mfaStore.disableAll(userId);
            mfaStore.deleteRecoveryCodes(userId);
            return null;
        });
    }

    public MfaStatusView status(UUID userId) {
        return tenantTransactions.inTenant(ctx -> new MfaStatusView(
                mfaStore.findActive(userId).isPresent(),
                mfaStore.remainingRecoveryCodes(userId)));
    }

    public boolean enabled(UUID userId) {
        return tenantTransactions.inTenant(ctx -> mfaStore.findActive(userId).isPresent());
    }
}
