package com.acme.platform.platform.work;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 工作状态的只读端口：让部门工作区与洞察读取工时状态，而不直接依赖 worklog 内部表。
 *
 * <p>实现方必须只返回「当前查看者有权看到」的状态；未授权的字段返回空值，
 * 由调用方整体省略，不得返回数量或摘要。
 */
public interface WorkStatusQuery {

    /** 批量读取某日状态；没有记录的成员不出现在结果里。 */
    Map<UUID, PersonWorkStatus> statuses(Collection<UUID> personIds, LocalDate date);

    /**
     * @param todayMinutes    当日已确认分钟数；无权限或未提交时为空
     * @param dayState        draft/submitted/approved/returned；无记录时为空
     * @param weekReportState draft/submitted；无记录时为空
     */
    record PersonWorkStatus(Integer todayMinutes, String dayState, String weekReportState) {
    }
}
