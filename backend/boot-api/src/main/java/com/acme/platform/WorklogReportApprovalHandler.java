package com.acme.platform;

import com.acme.platform.workflow.WorkflowBusinessHandler;
import com.acme.platform.worklog.report.WorklogReportService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 周报审批回调：定义驱动流程通过/退回时写回周报状态。 */
@Component
class WorklogReportApprovalHandler implements WorkflowBusinessHandler {

    static final String BUSINESS_TYPE = "worklog.report";

    private final WorklogReportService reports;

    WorklogReportApprovalHandler(WorklogReportService reports) {
        this.reports = reports;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public void approved(UUID businessId, UUID actorPersonId, String comment) {
        reports.review(actorPersonId, businessId, "approve", comment);
    }

    @Override
    public void returned(UUID businessId, UUID actorPersonId, String comment) {
        reports.review(actorPersonId, businessId, "return", comment);
    }
}
