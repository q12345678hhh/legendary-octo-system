package com.acme.platform.tenancy.retention;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.retention.RetentionTarget;
import com.acme.platform.platform.retention.RetentionTombstonePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 回收站、法定留置与到期清理的应用服务。
 *
 * <p>普通删除的判定顺序固定：存在性 → 留置 → 受保护数据类 → 幂等 → 入回收站。
 * 顺序不可调换，否则会出现「先入回收站后才发现被留置」的越权删除。
 * 所有方法都在 {@code TenantTransactions.inTenant(...)} 内执行，保证 RLS 上下文与写库同事务。
 */
@Service
public class RetentionService {

    private final RetentionStore store;
    private final LegalHoldStore holds;
    private final RetentionEventStore events;
    private final RetentionPolicy policy;
    private final List<RetentionTarget> targets;
    private final ObjectProvider<RetentionTombstonePort> tombstones;
    private final TenantTransactions transactions;
    private final ObjectMapper objectMapper;

    public RetentionService(RetentionStore store,
                            LegalHoldStore holds,
                            RetentionEventStore events,
                            RetentionPolicy policy,
                            List<RetentionTarget> targets,
                            ObjectProvider<RetentionTombstonePort> tombstones,
                            TenantTransactions transactions,
                            ObjectMapper objectMapper) {
        this.store = store;
        this.holds = holds;
        this.events = events;
        this.policy = policy;
        this.targets = targets;
        this.tombstones = tombstones;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public RetentionBinItem recycle(String dataClass, UUID objectId) {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context, ctx -> doRecycle(ctx, dataClass, objectId));
    }

    public RetentionBinItem restore(UUID binId) {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context, ctx -> {
            RetentionBinItem item = store.find(binId)
                    .orElseThrow(() -> ApiException.notFound("RETENTION_ITEM_NOT_FOUND", "回收站条目不存在"));
            if ("anonymized".equals(item.status())) {
                throw ApiException.conflict("RESTORE_BLOCKED_ANONYMIZED",
                        "该对象已完成个人信息匿名化，不允许恢复");
            }
            if (!"active".equals(item.status())) {
                throw ApiException.conflict("RETENTION_ITEM_NOT_ACTIVE",
                        "只有回收站中的条目可以恢复，当前状态：" + item.status());
            }
            target(item.dataClass()).restore(item.objectId());
            store.markRestored(binId, ctx.subjectId());
            events.record(ctx.tenantId(), "restored", item.dataClass(), item.objectId(),
                    ctx.subjectId(), Map.of("binItemId", binId.toString()));
            return store.find(binId).orElseThrow();
        });
    }

    public RetentionBinItem purgeNow(UUID binId) {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context, ctx -> {
            RetentionBinItem item = store.find(binId)
                    .orElseThrow(() -> ApiException.notFound("RETENTION_ITEM_NOT_FOUND", "回收站条目不存在"));
            if ("purged".equals(item.status())) {
                return item;
            }
            if (!"active".equals(item.status()) && !"anonymized".equals(item.status())) {
                throw ApiException.conflict("RETENTION_ITEM_NOT_ACTIVE",
                        "只有回收站中的条目可以清空，当前状态：" + item.status());
            }
            target(item.dataClass()).purge(item.objectId());
            store.markPurged(binId);
            tombstones.ifAvailable(port -> port.record(ctx.tenantId(), item.dataClass(), item.objectId(),
                    null, "purged", null));
            events.record(ctx.tenantId(), "purged", item.dataClass(), item.objectId(),
                    ctx.subjectId(), Map.of("binItemId", binId.toString()));
            return store.find(binId).orElseThrow();
        });
    }

    public List<RetentionBinItem> list(int limit) {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context,
                ctx -> store.list(ctx.tenantId(), Math.clamp(limit, 1, 200)));
    }

    public List<LegalHold> holds() {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context, ctx -> holds.listActive(ctx.tenantId()));
    }

    /** 到期清理：由 Worker 在租户上下文内调用，返回实际清理条数。 */
    public int purgeDue(int limit) {
        return purgeDue(RequestContextHolder.current(), limit);
    }

    public int purgeDue(RequestContext context, int limit) {
        return transactions.inTenant(context, ctx -> {
            List<RetentionBinItem> due = store.due(ctx.tenantId(), Math.clamp(limit, 1, 500));
            int purged = 0;
            for (RetentionBinItem item : due) {
                target(item.dataClass()).purge(item.objectId());
                if (store.markPurged(item.id()) > 0) {
                    tombstones.ifAvailable(port -> port.record(ctx.tenantId(), item.dataClass(),
                            item.objectId(), null, "purged", null));
                    events.record(ctx.tenantId(), "purged", item.dataClass(), item.objectId(),
                            "worker", Map.of("binItemId", item.id().toString(), "expired", true));
                    purged++;
                }
            }
            return purged;
        });
    }

    /**
     * 恢复后重放墓碑：对象又出现时再次清理。
     *
     * @return true 表示重放后对象已不存在（applied），false 表示无可清理内容（skipped）
     */
    public boolean replayPurge(String dataClass, UUID objectId) {
        return replayPurge(RequestContextHolder.current(), dataClass, objectId);
    }

    /** 平台侧重放使用显式上下文：平台请求线程没有租户上下文。 */
    public boolean replayPurge(RequestContext context, String dataClass, UUID objectId) {
        return transactions.inTenant(context, ctx -> {
            Optional<RetentionTarget> target = targets.stream()
                    .filter(candidate -> candidate.dataClass().equals(dataClass))
                    .findFirst();
            if (target.isEmpty()) {
                return false;
            }
            if (target.get().snapshot(objectId).isEmpty()) {
                return false;
            }
            target.get().purge(objectId);
            return target.get().snapshot(objectId).isEmpty();
        });
    }

    private RetentionBinItem doRecycle(RequestContext context, String dataClass, UUID objectId) {
        UUID tenantId = context.tenantId();

        Optional<LegalHold> hold = holds.findActive(tenantId, dataClass, objectId);
        if (hold.isPresent()) {
            LegalHold active = hold.get();
            throw ApiException.conflict("RETENTION_LEGAL_HOLD",
                    "存在法定留置：" + active.reason() + "（holdId=" + active.id() + "）");
        }
        ProtectedDataClass.of(dataClass).ifPresent(protectedClass -> {
            throw ApiException.conflict("RETENTION_PROTECTED", protectedClass.retentionReason());
        });

        Optional<RetentionBinItem> existing = store.findActive(tenantId, dataClass, objectId);
        if (existing.isPresent()) {
            return existing.get();
        }

        RetentionTarget target = target(dataClass);
        Map<String, Object> snapshot = target.snapshot(objectId)
                .orElseThrow(() -> ApiException.notFound("RETENTION_OBJECT_NOT_FOUND",
                        "对象不存在或不属于当前租户"));
        String snapshotJson = toJson(snapshot);
        Object ref = snapshot.get("objectRef");
        String objectRef = ref == null ? null : ref.toString();

        RetentionBinItem item = store.insert(tenantId, dataClass, objectId, objectRef, snapshotJson,
                context.subjectId(), policy.recycleBinDays());
        target.markRecycled(objectId);
        events.record(tenantId, "recycled", dataClass, objectId, context.subjectId(),
                Map.of("binItemId", item.id().toString(), "purgeAfter", item.purgeAfter().toString()));
        return item;
    }

    private RetentionTarget target(String dataClass) {
        return targets.stream()
                .filter(candidate -> candidate.dataClass().equals(dataClass))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("RETENTION_CLASS_UNSUPPORTED",
                        "不支持的数据类：" + dataClass));
    }

    private String toJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化回收站快照", e);
        }
    }
}
