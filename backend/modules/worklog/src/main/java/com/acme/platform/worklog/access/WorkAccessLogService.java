package com.acme.platform.worklog.access;

import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 工作访问留痕应用服务：把留痕写入与租户事务绑定。 */
@Service
public class WorkAccessLogService {

    private final WorkAccessLogStore store;
    private final TenantTransactions transactions;

    public WorkAccessLogService(WorkAccessLogStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public void record(UUID viewerPersonId, UUID subjectPersonId, String resourceType,
                       String resourceId, String action, List<String> factors) {
        transactions.inTenant(context -> {
            store.append(viewerPersonId, subjectPersonId, resourceType, resourceId, action, factors);
            return null;
        });
    }

    public List<WorkAccessLogStore.AccessEntry> recent(UUID subjectPersonId, int limit) {
        return transactions.inTenant(context -> store.recent(subjectPersonId, limit));
    }
}
