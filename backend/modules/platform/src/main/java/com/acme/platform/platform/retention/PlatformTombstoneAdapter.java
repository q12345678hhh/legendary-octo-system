package com.acme.platform.platform.retention;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把租户侧的清理动作落到平台墓碑表。
 */
@Component
public class PlatformTombstoneAdapter implements RetentionTombstonePort {

    private final TombstoneStore store;

    public PlatformTombstoneAdapter(TombstoneStore store) {
        this.store = store;
    }

    @Override
    public void record(UUID tenantId, String dataClass, UUID objectId, String subjectHash,
                       String action, UUID requestId) {
        store.record(tenantId, dataClass, objectId, subjectHash, action, requestId,
                Map.of("source", "tenant-retention"));
    }
}
