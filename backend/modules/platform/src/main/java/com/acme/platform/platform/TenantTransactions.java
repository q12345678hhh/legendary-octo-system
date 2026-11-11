package com.acme.platform.platform;

import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把「租户上下文」和「业务写库」绑在同一个事务里。
 *
 * <p>RLS 只认事务级 {@code set_config(..., true)}：如果先设置上下文再另起事务写库，
 * 上下文在提交时就失效了，写入会撞 WITH CHECK。所以所有租户表的读写都必须经本类。
 *
 * <p>事务传播是 REQUIRED：外层已经有事务时直接加入，不会把上层业务拆成两个事务。
 */
@Component
public class TenantTransactions {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TenantTransactions(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public <T> T inTenant(Function<RequestContext, T> work) {
        return inTenant(RequestContextHolder.current(), work);
    }

    public <T> T inTenant(RequestContext context, Function<RequestContext, T> work) {
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("租户事务缺少合法租户：请求必须携带 X-Tenant-Id");
        }
        return transactionTemplate.execute(status -> {
            bind("app.tenant_id", context.tenantId().toString());
            bind("app.subject_id", context.subjectId() == null ? "" : context.subjectId());
            bind("app.trace_id", context.traceId());
            return work.apply(context);
        });
    }

    private void bind(String key, String value) {
        // set_config 的第三个参数为 true：事务结束自动失效，不污染连接池里的下一条请求
        jdbcTemplate.queryForObject("SELECT set_config(?, ?, true)", String.class, key, value);
    }
}
