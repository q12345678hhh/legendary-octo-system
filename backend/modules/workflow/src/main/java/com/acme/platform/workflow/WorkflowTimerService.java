package com.acme.platform.workflow;

import org.springframework.stereotype.Service;

/** 定时器扫描：到点后推进对应令牌；Worker 每 5 秒调用一次。 */
@Service
public class WorkflowTimerService {

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final WorkflowTenantWork tenantWork;

    public WorkflowTimerService(WorkflowStore store, WorkflowEngine engine,
                                WorkflowTenantWork tenantWork) {
        this.store = store;
        this.engine = engine;
        this.tenantWork = tenantWork;
    }

    public int fireDue() {
        return tenantWork.forEachActiveTenant(context -> {
            int fired = 0;
            for (WorkflowStore.TimerRow timer : store.dueTimers(50)) {
                store.markTimerFired(timer.id());
                engine.resume(timer.instanceId(), timer.tokenId());
                fired++;
            }
            return fired;
        }).stream().mapToInt(Integer::intValue).sum();
    }
}
