package com.acme.platform.workflow;

import java.util.List;
import java.util.UUID;

/**
 * 业务回调 SPI：由 boot-api 实现，把审批结果写回业务库。
 *
 * <p>实现必须运行在调用方已开启的租户事务里；抛出异常表示业务写入失败，
 * 任务保持待办并记录 {@code BUSINESS_ACTION_FAILED} 异常，不静默成功。
 */
public interface WorkflowBusinessHandler {

    /** 业务类型，如 {@code worklog.time} / {@code worklog.report}。 */
    String businessType();

    void approved(UUID businessId, UUID actorPersonId, String comment);

    void returned(UUID businessId, UUID actorPersonId, String comment);

    /** 通用流程没有业务回调，完成审批即结束。 */
    static List<WorkflowBusinessHandler> none() {
        return List.of();
    }
}
