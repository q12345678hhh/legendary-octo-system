package com.acme.platform.platform.operations;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 作业与审计：迁移记录、备份/演练记录、队列健康与平台审计查询。 */
@Service
public class PlatformOperationService {

    private static final Set<String> KINDS = Set.of(
            "migration", "backup", "restore_drill", "queue_requeue", "catalog_seed");
    private static final Set<String> STATUSES = Set.of("running", "succeeded", "failed");

    public record OperationsOverview(PlatformOperationStore.OperationRun latestMigration,
                                     long outboxPending,
                                     long outboxFailed,
                                     long outboxDead,
                                     long runsLast24h,
                                     OffsetDateTime generatedAt) {
    }

    private final PlatformOperationStore store;
    private final PlatformAuditStore auditStore;

    public PlatformOperationService(PlatformOperationStore store, PlatformAuditStore auditStore) {
        this.store = store;
        this.auditStore = auditStore;
    }

    public OperationsOverview overview() {
        return new OperationsOverview(
                store.latest("migration").orElse(null),
                store.countOutbox("pending"),
                store.countOutbox("failed"),
                store.countOutbox("dead"),
                store.list(null, 200).stream()
                        .filter(run -> run.startedAt().isAfter(OffsetDateTime.now().minusDays(1)))
                        .count(),
                OffsetDateTime.now());
    }

    public List<PlatformOperationStore.OperationRun> runs(String kind, int limit) {
        if (kind != null && !kind.isBlank() && !KINDS.contains(kind)) {
            throw ApiException.badRequest("OPERATION_KIND_INVALID", "未知运行类型：" + kind);
        }
        return store.list(kind, limit);
    }

    public PlatformOperationStore.OperationRun record(String kind, UUID tenantId, String status,
                                                      String summary, PlatformOperatorRef operator) {
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("OPERATION_KIND_INVALID", "未知运行类型：" + kind);
        }
        if (!STATUSES.contains(status)) {
            throw ApiException.badRequest("OPERATION_STATUS_INVALID", "未知运行状态：" + status);
        }
        String trimmed = summary == null ? "" : summary.trim();
        if (trimmed.length() < 4 || trimmed.length() > 500) {
            throw ApiException.badRequest("OPERATION_SUMMARY_INVALID", "摘要长度必须在 4–500 个字符之间");
        }
        UUID id = store.insert(kind, tenantId, status, trimmed, operator.operatorId(), null, null);
        auditStore.record(operator, "operations.run.record", "platform_operation_run", id.toString(),
                Map.of("kind", kind, "status", status));
        return store.list(kind, 50).stream()
                .filter(run -> run.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("OPERATION_RUN_NOT_FOUND", "运行记录不存在"));
    }

    public List<PlatformOperationStore.AuditEvent> audit(int limit) {
        return store.audit(limit);
    }
}
