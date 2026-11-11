package com.acme.platform.worklog.update;

import com.acme.platform.worklog.device.CanonicalForms;
import com.acme.platform.worklog.device.WorkAssistantKeys;
import com.acme.platform.worklog.device.WorkAssistantProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/** 签发更新清单；未配置密钥或包哈希时由控制器返回 503，不返回「未签名清单」。 */
@Service
public class UpdateManifestService {

    private final WorkAssistantProperties properties;
    private final WorkAssistantKeys keys;

    public UpdateManifestService(WorkAssistantProperties properties, WorkAssistantKeys keys) {
        this.properties = properties;
        this.keys = keys;
    }

    public boolean configured() {
        return keys.updateConfigured()
                && !properties.getUpdatePackageSha256().isBlank()
                && !properties.getUpdatePackageUrl().isBlank();
    }

    public UpdateManifest current() {
        if (!configured()) {
            return null;
        }
        String releasedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String canonical = CanonicalForms.update(
                properties.getUpdateVersion(),
                properties.getUpdatePackageSha256(),
                properties.getUpdatePackageUrl(),
                properties.getMinSupportedVersion(),
                releasedAt);
        return new UpdateManifest(
                properties.getUpdateVersion(),
                properties.getUpdatePackageSha256(),
                properties.getUpdatePackageUrl(),
                properties.getMinSupportedVersion(),
                releasedAt,
                keys.updateKeyId(),
                keys.signUpdate(canonical));
    }
}
