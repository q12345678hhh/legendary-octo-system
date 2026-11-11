package com.acme.platform.tenancy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import org.springframework.stereotype.Component;

/**
 * 租户执行门禁：业务写入、插件与 AI 新执行的统一入口。
 *
 * <p>冻结（frozen）或注销冷静期（closing）会阻断三类执行，只保留受控导出与申诉；
 * 已删除（deleted）返回 410。插件与 AI 模块必须在真正执行前调用对应方法，
 * 不能只依赖 API 层的写请求过滤，否则异步任务可以绕过去。
 */
@Component
public class TenantExecutionGate {

    private final TenantStatusQueries queries;

    public TenantExecutionGate(TenantStatusQueries queries) {
        this.queries = queries;
    }

    public TenantRecord assertBusinessWrite(RequestContext context) {
        return requireExecutable(context, "TENANT_FROZEN", "业务写入");
    }

    public TenantRecord assertPluginExecution(RequestContext context) {
        return requireExecutable(context, "TENANT_PLUGIN_BLOCKED", "插件执行");
    }

    public TenantRecord assertAiExecution(RequestContext context) {
        return requireExecutable(context, "TENANT_AI_BLOCKED", "AI 新执行");
    }

    private TenantRecord requireExecutable(RequestContext context, String code, String action) {
        if (context == null || context.tenantId() == null) {
            throw ApiException.badRequest("TENANT_REQUIRED", "缺少租户上下文，无法执行" + action);
        }
        TenantRecord tenant = queries.find(context).orElseThrow(() -> ApiException.notFound(
                "TENANT_NOT_FOUND", "租户不存在或当前上下文无权访问"));
        if (tenant.status() == TenantStatus.DELETED) {
            throw new ApiException(410, "TENANT_DELETED", "租户已最终删除，不能再执行" + action);
        }
        if (!tenant.status().executable()) {
            throw new ApiException(423, code,
                    "租户状态为 " + tenant.status().wire() + "，已阻断" + action
                            + "；仅保留受控导出与申诉");
        }
        return tenant;
    }
}
