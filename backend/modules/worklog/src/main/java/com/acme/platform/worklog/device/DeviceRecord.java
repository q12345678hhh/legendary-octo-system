package com.acme.platform.worklog.device;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 设备登记快照；不包含任何私钥或「远程开启采集」字段。 */
public record DeviceRecord(
        UUID id,
        UUID tenantId,
        String subjectId,
        String deviceName,
        String publicKeySpki,
        String appVersion,
        int policyVersion,
        String status,
        OffsetDateTime registeredAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime revokedAt,
        String revokedBy) {

    public boolean active() {
        return "active".equals(status);
    }
}
