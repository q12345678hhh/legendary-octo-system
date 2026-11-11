package com.acme.platform.tenancy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 在请求上下文内读取租户状态。
 *
 * <p>过滤器与执行门禁都复用它，避免各自拼 SQL 绕过 RLS 或忘记事务级租户上下文。
 */
@Component
public class TenantStatusQueries {

    private final TenantTransactions tenantTransactions;
    private final TenantStore tenantStore;

    public TenantStatusQueries(TenantTransactions tenantTransactions, TenantStore tenantStore) {
        this.tenantTransactions = tenantTransactions;
        this.tenantStore = tenantStore;
    }

    public Optional<TenantRecord> find(RequestContext context) {
        return tenantTransactions.inTenant(context, ctx -> tenantStore.find(ctx.tenantId()));
    }

    public TenantRecord require(RequestContext context) {
        return find(context).orElseThrow(() -> ApiException.notFound(
                "TENANT_NOT_FOUND", "租户不存在或当前上下文无权访问"));
    }
}
