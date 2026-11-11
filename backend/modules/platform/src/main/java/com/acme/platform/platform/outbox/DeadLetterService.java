package com.acme.platform.platform.outbox;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 死信处理：查询按租户过滤，回放必须写明原因并留审计。
 *
 * <p>回放只把事件重新置为待投递，不做业务补偿——如果处理器不幂等，
 * 重投可能产生第二次副作用，所以回放前的幂等性确认是操作人的责任，写进运行手册。
 */
@Service
public class DeadLetterService {

    private final DeadLetterStore store;
    private final TenantTransactions transactions;
    private final PlatformMetrics metrics;

    public DeadLetterService(DeadLetterStore store, TenantTransactions transactions,
                             PlatformMetrics metrics) {
        this.store = store;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    public List<DeadLetterRecord> list(int limit) {
        RequestContext context = requireTenant();
        int bounded = Math.max(1, Math.min(limit, 100));
        return transactions.inTenant(context, ctx -> store.list(ctx.tenantId(), bounded));
    }

    public void replay(UUID eventId, String reason) {
        RequestContext context = requireTenant();
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < 8 || normalized.length() > 500) {
            throw ApiException.unprocessable("REPLAY_REASON_REQUIRED",
                    "回放原因必填，长度 8-500 字符，用于审计与事故复盘");
        }
        String actor = context.subjectId() == null ? "api" : context.subjectId();
        transactions.inTenant(context, ctx -> {
            if (!store.markForReplay(eventId, ctx.tenantId())) {
                throw ApiException.notFound("DEAD_LETTER_NOT_FOUND",
                        "事件不存在、不属于当前租户或已不在死信状态");
            }
            store.audit(eventId, ctx.tenantId(), normalized, actor);
            return null;
        });
        metrics.outboxReplayed();
    }

    private static RequestContext requireTenant() {
        RequestContext context = RequestContextHolder.current();
        if (context == null || context.tenantId() == null) {
            throw ApiException.badRequest("TENANT_REQUIRED", "请求必须携带 X-Tenant-Id");
        }
        return context;
    }
}
