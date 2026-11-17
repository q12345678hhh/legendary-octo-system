package com.acme.platform.platform.retention;

import java.util.UUID;

/**
 * 墓碑写入端口：把租户侧的清理动作落到平台墓碑表。
 */
public interface RetentionTombstonePort {

    void record(UUID tenantId, String dataClass, UUID objectId, String subjectHash, String action, UUID requestId);
}
