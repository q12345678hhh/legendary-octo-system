package com.acme.platform.worklog.device;

import com.acme.platform.platform.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 设备请求验签。
 *
 * <p>判定顺序是「存在 → 未撤销 → 签名有效 → 时间窗」，任何失败都返回同一个 403，
 * 不向未认证方泄露设备是否存在。时间窗与片段唯一键共同限制重放危害。
 */
@Component
public class DeviceAuthenticator {

    private final DeviceStore deviceStore;
    private final WorkAssistantProperties properties;

    public DeviceAuthenticator(DeviceStore deviceStore, WorkAssistantProperties properties) {
        this.deviceStore = deviceStore;
        this.properties = properties;
    }

    public DeviceRecord authenticate(UUID deviceId, String method, String path,
                                     String timestamp, String signature, String body) {
        DeviceRecord device = deviceStore.find(deviceId)
                .orElseThrow(() -> ApiException.forbidden("DEVICE_NOT_REGISTERED", "设备未注册"));
        if (!device.active()) {
            throw ApiException.forbidden("DEVICE_REVOKED", "设备已撤销，不能继续上传");
        }
        String canonical = CanonicalForms.request(method, path, timestamp, body);
        if (!WorkAssistantKeys.verify(device.publicKeySpki(), canonical, signature)) {
            throw ApiException.forbidden("DEVICE_SIGNATURE_INVALID", "设备签名无效");
        }
        if (!withinWindow(timestamp)) {
            throw ApiException.forbidden("DEVICE_CLOCK_SKEW", "设备时间戳超出允许窗口");
        }
        return device;
    }

    private boolean withinWindow(String timestamp) {
        try {
            long seconds = Long.parseLong(timestamp);
            Duration skew = properties.getClockSkew();
            return Math.abs(Instant.now().getEpochSecond() - seconds) <= skew.toSeconds();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
