package com.acme.platform.platform.retention;

import com.acme.platform.platform.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 数据主体请求案件的状态机。
 *
 * <p>状态迁移只允许：received/verifying → approved/rejected/cancelled，
 * approved → executing → completed/failed。任何越级迁移都返回 409。
 */
@Service
public class DeletionRequestService {

    private static final List<String> PENDING = List.of("received", "verifying");

    private final DeletionRequestStore store;

    public DeletionRequestService(DeletionRequestStore store) {
        this.store = store;
    }

    public DeletionRequest submit(UUID tenantId, String subjectType, UUID subjectId, String reason, String actor) {
        String hash = subjectHash(tenantId, subjectId);
        return store.insert(tenantId, subjectType, subjectId, hash, reason, actor);
    }

    public DeletionRequest get(UUID id) {
        return store.find(id).orElseThrow(() ->
                ApiException.notFound("DELETION_REQUEST_NOT_FOUND", "删除请求不存在"));
    }

    public List<DeletionRequest> listByTenant(UUID tenantId, int limit) {
        return store.listByTenant(tenantId, Math.clamp(limit, 1, 200));
    }

    public List<DeletionRequest> listByStatus(String status, int limit) {
        return store.listByStatus(status == null || status.isBlank() ? "received" : status,
                Math.clamp(limit, 1, 200));
    }

    public DeletionRequest startVerifying(UUID id) {
        DeletionRequest request = get(id);
        if (!PENDING.contains(request.status())) {
            throw ApiException.conflict("DELETION_REQUEST_STATE_INVALID",
                    "只有 received/verifying 状态可以进入核验，当前状态：" + request.status());
        }
        store.markVerifying(id);
        return get(id);
    }

    public DeletionRequest approve(UUID id, String decisionCode, String note, String actor) {
        requirePending(id);
        store.markApproved(id, decisionCode, note, actor);
        return get(id);
    }

    public DeletionRequest reject(UUID id, String decisionCode, String note, String actor) {
        if (decisionCode == null || decisionCode.isBlank()) {
            throw ApiException.badRequest("DELETION_REQUEST_DECISION_REQUIRED", "拒绝必须给出判定原因码");
        }
        requirePending(id);
        store.markRejected(id, decisionCode, note, actor);
        return get(id);
    }

    public DeletionRequest startExecution(UUID id, String actor) {
        DeletionRequest request = get(id);
        if (!"approved".equals(request.status())) {
            throw ApiException.conflict("DELETION_REQUEST_STATE_INVALID",
                    "只有 approved 状态可以执行，当前状态：" + request.status());
        }
        store.markExecuting(id, actor);
        return get(id);
    }

    public DeletionRequest complete(UUID id) {
        if (store.markCompleted(id, java.time.OffsetDateTime.now()) == 0) {
            throw ApiException.conflict("DELETION_REQUEST_STATE_INVALID", "只有 executing 状态可以完成");
        }
        return get(id);
    }

    public DeletionRequest fail(UUID id, String note) {
        store.markFailed(id, note);
        return get(id);
    }

    public DeletionRequest cancel(UUID id, UUID tenantId, String actor) {
        DeletionRequest request = get(id);
        if (!request.tenantId().equals(tenantId)) {
            throw ApiException.notFound("DELETION_REQUEST_NOT_FOUND", "删除请求不存在");
        }
        if (!PENDING.contains(request.status())) {
            throw ApiException.conflict("DELETION_REQUEST_STATE_INVALID",
                    "只有 received/verifying 状态可以撤回，当前状态：" + request.status());
        }
        store.markCancelled(id);
        return get(id);
    }

    /** 主体哈希：租户 + 主体 ID 的 SHA-256，案件表不落任何可读个人信息。 */
    public static String subjectHash(UUID tenantId, UUID subjectId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((tenantId + ":" + subjectId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算主体哈希", e);
        }
    }

    private void requirePending(UUID id) {
        DeletionRequest request = get(id);
        if (!PENDING.contains(request.status())) {
            throw ApiException.conflict("DELETION_REQUEST_STATE_INVALID",
                    "只有 received/verifying 状态可以判定，当前状态：" + request.status());
        }
    }
}
