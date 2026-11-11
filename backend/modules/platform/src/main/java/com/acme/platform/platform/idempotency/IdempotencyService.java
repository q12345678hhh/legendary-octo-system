package com.acme.platform.platform.idempotency;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 幂等协调器：先抢执行权，再缓存首次响应。
 *
 * <p>核心保证是「同一主体、同一端点、同一幂等键、同一请求内容」只会真正执行一次。
 * 幂等键复用但内容不同必须拒绝（422），否则把旧响应当成新请求的结果会掩盖调用方 bug。
 */
@Component
public class IdempotencyService {

    public enum Outcome {
        /** 抢到执行权，本次请求继续往下走。 */
        ACQUIRED,
        /** 首次请求已完成，直接重放缓存响应。 */
        REPLAY,
        /** 首次请求还在执行，调用方应立即重试或稍后再查。 */
        IN_PROGRESS,
        /** 幂等键相同但请求内容不同。 */
        KEY_REUSED
    }

    public record Claim(Outcome outcome, UUID recordId, int status, String contentType, String body) {
    }

    private final IdempotencyStore store;
    private final TenantTransactions transactions;
    private final Duration ttl;

    public IdempotencyService(IdempotencyStore store,
                              TenantTransactions transactions,
                              @Value("${platform.idempotency.ttl:24h}") Duration ttl) {
        this.store = store;
        this.transactions = transactions;
        this.ttl = ttl;
    }

    public Claim claim(String endpoint, String key, String requestHash) {
        RequestContext context = RequestContextHolder.current();
        requireIdentity(context);
        return transactions.inTenant(context, ctx -> doClaim(ctx, endpoint, key, requestHash));
    }

    public void complete(UUID recordId, int status, String contentType, byte[] body) {
        RequestContext context = RequestContextHolder.current();
        requireIdentity(context);
        String text = body == null || body.length == 0 ? null : new String(body, StandardCharsets.UTF_8);
        transactions.inTenant(context, ctx -> {
            store.complete(recordId, status, contentType, text);
            return null;
        });
    }

    public void release(UUID recordId) {
        RequestContext context = RequestContextHolder.current();
        if (context == null || context.tenantId() == null) {
            return;
        }
        transactions.inTenant(context, ctx -> {
            store.release(recordId);
            return null;
        });
    }

    private Claim doClaim(RequestContext context, String endpoint, String key, String requestHash) {
        UUID candidate = UUID.randomUUID();
        if (store.tryInsert(candidate, context.tenantId(), context.subjectId(), endpoint, key,
                requestHash, ttl)) {
            return new Claim(Outcome.ACQUIRED, candidate, 0, null, null);
        }

        IdempotencyRecord existing = store.find(context.subjectId(), endpoint, key).orElse(null);
        if (existing == null) {
            // 极窄竞态：同键记录刚被清理。再抢一次，抢不到就按进行中处理。
            boolean acquired = store.tryInsert(candidate, context.tenantId(), context.subjectId(),
                    endpoint, key, requestHash, ttl);
            return acquired
                    ? new Claim(Outcome.ACQUIRED, candidate, 0, null, null)
                    : new Claim(Outcome.IN_PROGRESS, null, 0, null, null);
        }

        if (existing.expiresAt().isBefore(OffsetDateTime.now())) {
            store.release(existing.id());
            boolean acquired = store.tryInsert(candidate, context.tenantId(), context.subjectId(),
                    endpoint, key, requestHash, ttl);
            return acquired
                    ? new Claim(Outcome.ACQUIRED, candidate, 0, null, null)
                    : new Claim(Outcome.IN_PROGRESS, null, 0, null, null);
        }

        if (!existing.requestHash().equals(requestHash)) {
            return new Claim(Outcome.KEY_REUSED, existing.id(), 0, null, null);
        }

        if (existing.completed()) {
            return new Claim(Outcome.REPLAY, existing.id(),
                    existing.responseStatus() == null ? 200 : existing.responseStatus(),
                    existing.responseContentType(), existing.responseBody());
        }

        return new Claim(Outcome.IN_PROGRESS, existing.id(), 0, null, null);
    }

    private static void requireIdentity(RequestContext context) {
        if (context == null || context.tenantId() == null || context.subjectId() == null) {
            throw new IllegalStateException("幂等需要租户与主体：请求必须携带 X-Tenant-Id 且已完成认证");
        }
    }
}
