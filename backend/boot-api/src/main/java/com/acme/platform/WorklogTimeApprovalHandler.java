package com.acme.platform;

import com.acme.platform.workflow.WorkflowBusinessHandler;
import com.acme.platform.worklog.time.WorklogSubmissionService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工时审批回调：定义驱动流程在审批通过/退回时写回工时业务库。
 *
 * <p>与工时模块自己的直连审核共用 {@code WorklogSubmissionService.review}，
 * 保证两条路径的校验、留痕与返回结构完全一致。
 */
@Component
class WorklogTimeApprovalHandler implements WorkflowBusinessHandler {

    static final String BUSINESS_TYPE = "worklog.time";

    private final WorklogSubmissionService submissions;

    WorklogTimeApprovalHandler(WorklogSubmissionService submissions) {
        this.submissions = submissions;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public void approved(UUID businessId, UUID actorPersonId, String comment) {
        submissions.review(actorPersonId, businessId, "approve", comment);
    }

    @Override
    public void returned(UUID businessId, UUID actorPersonId, String comment) {
        submissions.review(actorPersonId, businessId, "return", comment);
    }
}
