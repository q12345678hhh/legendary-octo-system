package com.acme.platform;

import com.acme.platform.organization.retention.PersonAnonymizer;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import com.acme.platform.platform.operations.PlatformOperationStore;
import com.acme.platform.platform.retention.TombstoneStore;
import com.acme.platform.tenancy.retention.RetentionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 恢复后重放删除墓碑：把备份里「复活」的对象再次清理或匿名化。
 *
 * <p>只处理两类墓碑：已物理清理的对象（file / work_assistant_segment）与
 * 已匿名化的自然人。重放结果逐条记录，接口可重复调用。
 */
@Service
class TombstoneReplayService {

    record ReplayResult(int applied, int skipped, int failed) {
    }

    private final TombstoneStore tombstones;
    private final RetentionService retention;
    private final PersonAnonymizer persons;
    private final TenantTransactions transactions;
    private final PlatformOperationStore operations;
    private final PlatformAuditStore auditStore;

    TombstoneReplayService(TombstoneStore tombstones,
                           RetentionService retention,
                           PersonAnonymizer persons,
                           TenantTransactions transactions,
                           PlatformOperationStore operations,
                           PlatformAuditStore auditStore) {
        this.tombstones = tombstones;
        this.retention = retention;
        this.persons = persons;
        this.transactions = transactions;
        this.operations = operations;
        this.auditStore = auditStore;
    }

    ReplayResult replay(int limit, PlatformOperatorRef operator) {
        List<TombstoneStore.Tombstone> pending = tombstones.listRecent(limit);
        int applied = 0;
        int skipped = 0;
        int failed = 0;
        for (TombstoneStore.Tombstone tombstone : pending) {
            try {
                boolean changed = apply(tombstone);
                tombstones.recordReplay(tombstone.id(), tombstone.tenantId(),
                        changed ? "applied" : "skipped",
                        Map.of("dataClass", tombstone.dataClass(), "action", tombstone.action()));
                if (changed) {
                    applied++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                tombstones.recordReplay(tombstone.id(), tombstone.tenantId(), "failed",
                        Map.of("error", String.valueOf(e.getMessage())));
            }
        }

        String summary = "applied=" + applied + ", skipped=" + skipped + ", failed=" + failed;
        operations.insert("tombstone_replay", null, failed == 0 ? "succeeded" : "failed",
                summary, operator.operatorId(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        auditStore.record(operator, "retention.tombstone.replay", "platform_deletion_tombstone",
                null, Map.of("applied", applied, "skipped", skipped, "failed", failed));
        return new ReplayResult(applied, skipped, failed);
    }

    private boolean apply(TombstoneStore.Tombstone tombstone) {
        if (tombstone.objectId() == null) {
            return false;
        }
        if ("person".equals(tombstone.dataClass()) && "anonymized".equals(tombstone.action())) {
            String hash8 = tombstone.subjectHash() == null || tombstone.subjectHash().length() < 8
                    ? "replayed"
                    : tombstone.subjectHash().substring(0, 8);
            RequestContext context = new RequestContext(tombstone.tenantId(), "tombstone-replay",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString());
            Integer updated = transactions.inTenant(context,
                    ctx -> persons.anonymize(tombstone.objectId(), hash8));
            return updated != null && updated > 0;
        }
        if ("purged".equals(tombstone.action())) {
            RequestContext context = new RequestContext(tombstone.tenantId(), "tombstone-replay",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString());
            return retention.replayPurge(context, tombstone.dataClass(), tombstone.objectId());
        }
        return false;
    }
}
