package com.acme.platform.worklog.device;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.worklog.policy.PolicyService;
import com.acme.platform.worklog.policy.SignedPolicy;
import com.acme.platform.worklog.update.UpdateManifest;
import com.acme.platform.worklog.update.UpdateManifestService;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 设备生命周期：注册、心跳（版本报告）、策略拉取与撤销。
 *
 * <p>这里没有任何「开始采集」入口——采集只由员工本人在托盘操作。
 * 撤销后的设备在验签阶段即被拒绝，上传不会再落库。
 */
@Service
public class DeviceService {

    private final DeviceStore deviceStore;
    private final DeviceAuthenticator authenticator;
    private final PolicyService policyService;
    private final UpdateManifestService updateManifestService;
    private final WorkAssistantKeys keys;
    private final WorkAssistantProperties properties;
    private final TenantTransactions transactions;

    public DeviceService(DeviceStore deviceStore, DeviceAuthenticator authenticator,
                         PolicyService policyService, UpdateManifestService updateManifestService,
                         WorkAssistantKeys keys, WorkAssistantProperties properties,
                         TenantTransactions transactions) {
        this.deviceStore = deviceStore;
        this.authenticator = authenticator;
        this.policyService = policyService;
        this.updateManifestService = updateManifestService;
        this.keys = keys;
        this.properties = properties;
        this.transactions = transactions;
    }

    public Registration register(UUID deviceId, String deviceName, String publicKeySpki, String appVersion) {
        if (deviceId == null) {
            throw ApiException.badRequest("DEVICE_ID_REQUIRED", "设备 ID 不能为空");
        }
        String name = deviceName == null ? "" : deviceName.trim();
        if (name.isEmpty() || name.length() > 200) {
            throw ApiException.unprocessable("DEVICE_NAME_INVALID", "设备名称必须为 1-200 个字符");
        }
        requireValidPublicKey(publicKeySpki);
        String version = normalizeVersion(appVersion);

        return transactions.inTenant(context -> {
            String subjectId = requireSubject(context);
            DeviceRecord existing = deviceStore.find(deviceId).orElse(null);
            if (existing != null) {
                if (!existing.publicKeySpki().equals(publicKeySpki)) {
                    throw ApiException.conflict("DEVICE_ID_CONFLICT", "设备 ID 已被另一把公钥占用");
                }
                if (!existing.active()) {
                    throw ApiException.forbidden("DEVICE_REVOKED", "设备已撤销，必须由管理员重新登记");
                }
                return registration(existing.id(), existing.status(), policyService.current());
            }
            deviceStore.find(subjectId, publicKeySpki).ifPresent(other -> {
                throw ApiException.conflict("DEVICE_KEY_REGISTERED", "该公钥已注册为设备 " + other.id());
            });
            DeviceRecord created = deviceStore.insert(deviceId, context.tenantId(), subjectId,
                    name, publicKeySpki, version);
            return registration(created.id(), created.status(), policyService.current());
        });
    }

    public HeartbeatResult heartbeat(UUID deviceId, String method, String path,
                                     String timestamp, String signature, String body,
                                     HeartbeatRequest request) {
        return transactions.inTenant(context -> {
            DeviceRecord device = authenticator.authenticate(deviceId, method, path, timestamp, signature, body);
            String appVersion = normalizeVersion(request == null ? null : request.appVersion());
            deviceStore.touch(device.id(), appVersion, policyService.currentVersion(context.tenantId()));
            boolean updateRequired = compareVersions(appVersion, properties.getMinSupportedVersion()) < 0;
            UpdateManifest manifest = updateManifestService.configured()
                    ? updateManifestService.current() : null;
            return new HeartbeatResult(device.status(), properties.getMinSupportedVersion(),
                    updateRequired, manifest);
        });
    }

    public SignedPolicy policy(UUID deviceId, String method, String path,
                               String timestamp, String signature) {
        return transactions.inTenant(context -> {
            authenticator.authenticate(deviceId, method, path, timestamp, signature, "");
            return policyService.current();
        });
    }

    public void revoke(UUID deviceId, String revokedBy) {
        transactions.inTenant(context -> {
            if (!deviceStore.revoke(deviceId, revokedBy)) {
                throw ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在或已撤销");
            }
            return null;
        });
    }

    private Registration registration(UUID deviceId, String status, SignedPolicy policy) {
        return new Registration(deviceId, status, policy,
                keys.policyPublicKeySpki(), keys.policyKeyId(),
                keys.updateConfigured() ? keys.updatePublicKeySpki() : null,
                keys.updateConfigured() ? keys.updateKeyId() : null,
                Instant.now().toString());
    }

    private static String requireSubject(RequestContext context) {
        if (context.subjectId() == null) {
            throw ApiException.badRequest("SUBJECT_REQUIRED", "设备注册必须携带 X-Subject-Id");
        }
        return context.subjectId();
    }

    private static void requireValidPublicKey(String publicKeySpki) {
        if (publicKeySpki == null || publicKeySpki.isBlank()) {
            throw ApiException.unprocessable("DEVICE_KEY_REQUIRED", "设备公钥不能为空");
        }
        try {
            KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpki)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw ApiException.unprocessable("DEVICE_KEY_INVALID", "设备公钥不是合法的 ECDSA SPKI");
        }
    }

    private static String normalizeVersion(String appVersion) {
        String value = appVersion == null ? "" : appVersion.trim();
        if (value.isEmpty() || value.length() > 64) {
            throw ApiException.unprocessable("APP_VERSION_INVALID", "应用版本必须为 1-64 个字符");
        }
        return value;
    }

    /** 只比较数字段；非数字后缀忽略，足够本阶段的最低版本门禁使用。 */
    static int compareVersions(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parsePart(a[i]) : 0;
            int y = i < b.length ? parsePart(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parsePart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record HeartbeatRequest(String appVersion, Integer policyVersion) {
    }

    public record HeartbeatResult(String status, String minSupportedVersion,
                                  boolean updateRequired, UpdateManifest updateManifest) {
    }

    public record Registration(UUID deviceId, String status, SignedPolicy policy,
                               String policyPublicKeySpki, String policyKeyId,
                               String updatePublicKeySpki, String updateKeyId, String serverTime) {
    }

    public record RevokeResult(String status) {
    }
}
