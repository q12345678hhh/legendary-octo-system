package com.acme.platform;

import com.acme.platform.identity.retention.AccountAnonymizer;
import com.acme.platform.organization.retention.PersonAnonymizer;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import com.acme.platform.platform.retention.DeletionRequest;
import com.acme.platform.platform.retention.DeletionRequestService;
import com.acme.platform.platform.retention.TombstoneStore;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 数据主体请求的执行编排。
 *
 * <p>匿名化跨 identity 与 organization 两个模块，编排放在 boot 层，
 * 避免让任一领域模块反向依赖另一个模块的写能力。
 */
@Service
class DataSubjectRequestOrchestrator {

    private final DeletionRequestService requests;
    private final PersonAnonymizer persons;
    private final AccountAnonymizer accounts;
    private final TombstoneStore tombstones;
    private final TenantTransactions transactions;
    private final PlatformAuditStore auditStore;

    DataSubjectRequestOrchestrator(DeletionRequestService requests,
                                   PersonAnonymizer persons,
                                   AccountAnonymizer accounts,
                                   TombstoneStore tombstones,
                                   TenantTransactions transactions,
                                   PlatformAuditStore auditStore) {
        this.requests = requests;
        this.persons = persons;
        this.accounts = accounts;
        this.tombstones = tombstones;
        this.transactions = transactions;
        this.auditStore = auditStore;
    }

    DeletionRequest execute(UUID requestId, PlatformOperatorRef operator) {
        DeletionRequest request = requests.get(requestId);
        if (!"approved".equals(request.status())) {
            throw ApiException.conflict("DELETION_REQUEST_STATE_INVALID",
                    "只有 approved 状态可以执行，当前状态：" + request.status());
        }

        RequestContext context = contextFor(request, operator);
        PersonAnonymizer.PersonState state = transactions.inTenant(context,
                ctx -> persons.state(request.subjectId()));
        if (!state.exists()) {
            return requests.fail(requestId, "SUBJECT_NOT_FOUND");
        }
        if (state.activeEmployment()) {
            throw ApiException.conflict("DELETION_BLOCKED_ACTIVE_EMPLOYMENT",
                    "该自然人仍在职，不能执行匿名化；请先解除雇佣关系或改为部分匿名化");
        }

        requests.startExecution(requestId, operator.email());
        String hash8 = request.subjectHash().substring(0, 8);
        transactions.inTenant(context, ctx -> {
            persons.anonymize(request.subjectId(), hash8);
            accounts.anonymizeByPerson(request.subjectId(), hash8);
            return null;
        });

        tombstones.record(request.tenantId(), "person", request.subjectId(), request.subjectHash(),
                "anonymized", requestId, Map.of("subjectType", request.subjectType()));

        DeletionRequest completed = requests.complete(requestId);
        auditStore.record(operator, "privacy.request.execute", "platform_deletion_request",
                requestId.toString(), Map.of("tenantId", request.tenantId().toString()));
        return completed;
    }

    private RequestContext contextFor(DeletionRequest request, PlatformOperatorRef operator) {
        return new RequestContext(request.tenantId(), "platform:" + operator.email(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }
}
