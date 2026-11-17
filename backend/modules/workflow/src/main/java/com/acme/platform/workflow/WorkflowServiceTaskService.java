package com.acme.platform.workflow;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 服务任务执行：按 handler_key 查注册表；失败指数退避，超过 max_attempts 写异常。
 *
 * <p>幂等由 {@code idempotency_key} 唯一约束 + 状态条件更新双重保证；补偿任务
 * 不复用原令牌推进，避免重复走主流程。
 */
@Service
public class WorkflowServiceTaskService {

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final WorkflowTenantWork tenantWork;
    private final Map<String, WorkflowServiceHandler> handlers;

    public WorkflowServiceTaskService(WorkflowStore store, WorkflowEngine engine,
                                      WorkflowTenantWork tenantWork,
                                      List<WorkflowServiceHandler> handlers) {
        this.store = store;
        this.engine = engine;
        this.tenantWork = tenantWork;
        Map<String, WorkflowServiceHandler> registry = new LinkedHashMap<>();
        if (handlers != null) {
            for (WorkflowServiceHandler handler : handlers) {
                registry.put(handler.key(), handler);
            }
        }
        this.handlers = Map.copyOf(registry);
    }

    public int runDue() {
        return tenantWork.forEachActiveTenant(context -> {
            int ran = 0;
            for (WorkflowStore.ServiceTaskRow task : store.runnableServiceTasks(20)) {
                store.markServiceRunning(task.id());
                WorkflowServiceHandler handler = handlers.get(task.handlerKey());
                if (handler == null) {
                    fail(task, "未注册的服务任务处理器：" + task.handlerKey(), true);
                    ran++;
                    continue;
                }
                try {
                    handler.run(task.instanceId(), task.nodeKey(), task.payload());
                    store.markServiceSucceeded(task.id());
                    if (task.compensationOf() == null && task.tokenId() != null) {
                        engine.resume(task.instanceId(), task.tokenId());
                    }
                } catch (RuntimeException error) {
                    fail(task, error.getMessage(), false);
                }
                ran++;
            }
            return ran;
        }).stream().mapToInt(Integer::intValue).sum();
    }

    private void fail(WorkflowStore.ServiceTaskRow task, String message, boolean finalFailure) {
        String reason = message == null || message.isBlank() ? "服务任务执行失败" : message;
        int attempts = task.attempts() + 1;
        if (finalFailure || attempts >= task.maxAttempts()) {
            store.markServiceFailed(task.id(), reason, OffsetDateTime.now().plusYears(10));
            store.insertException(task.instanceId(), task.id(), "SERVICE_FAILED", reason,
                    "{\"serviceTaskId\":\"" + task.id() + "\"}");
            return;
        }
        long backoffSeconds = Math.min(300L, 5L << Math.min(attempts, 6));
        store.markServiceFailed(task.id(), reason,
                OffsetDateTime.now().plusSeconds(backoffSeconds));
    }
}
