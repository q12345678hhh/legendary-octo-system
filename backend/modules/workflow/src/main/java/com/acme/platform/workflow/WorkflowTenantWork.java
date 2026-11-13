package com.acme.platform.workflow;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Worker 的跨租户扫描：从平台目录投影枚举 active 租户，逐租户开启 RLS 事务。
 *
 * <p>单个租户失败只记录日志，不阻塞其他租户；异常的具体原因已写入
 * {@code workflow_exception} 或在租户事务内回滚。
 */
@Component
class WorkflowTenantWork {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTenantWork.class);

    private final JdbcTemplate jdbcTemplate;
    private final TenantTransactions transactions;

    WorkflowTenantWork(JdbcTemplate jdbcTemplate, TenantTransactions transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    <T> List<T> forEachActiveTenant(Function<RequestContext, T> work) {
        List<UUID> tenantIds = jdbcTemplate.query("""
                SELECT id FROM platform_tenant_directory
                 WHERE status = 'active'
                 ORDER BY id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class));
        List<T> results = new ArrayList<>();
        for (UUID tenantId : tenantIds) {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            RequestContext context = new RequestContext(tenantId, "workflow-worker", traceId);
            RequestContextHolder.set(context);
            try {
                results.add(transactions.inTenant(context, work));
            } catch (RuntimeException error) {
                log.warn("工作流 Worker 租户 {} 执行失败：{}", tenantId, error.getMessage());
            } finally {
                RequestContextHolder.clear();
            }
        }
        return results;
    }
}
