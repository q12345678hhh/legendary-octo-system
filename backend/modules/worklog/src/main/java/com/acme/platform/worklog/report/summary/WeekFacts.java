package com.acme.platform.worklog.report.summary;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 周报事实包：只来自员工已确认（submitted/approved）的日快照。
 *
 * <p>这是 AI 摘要唯一允许引用的事实来源；不含语音原文、转写文本或设备数据。
 * 所有数字都在这里由服务端计算，模型只负责组织文字。
 *
 * @param truncated 是否因输入长度上限截断了条目说明
 */
public record WeekFacts(
        LocalDate weekStart,
        LocalDate weekEnd,
        int totalMinutes,
        int confirmedDays,
        List<EntryFact> entries,
        List<ItemFact> items,
        List<DayFact> days,
        List<AnomalyFact> anomalies,
        boolean truncated) {

    /** 单条已确认工时；description 是员工填写的说明，不是语音原文。 */
    public record EntryFact(UUID entryId, LocalDate date, int minutes,
                            UUID projectId, UUID taskId, String description) {
    }

    /** 按项目/任务聚合的时长，entryIds 支撑逐项回溯。 */
    public record ItemFact(String key, int minutes, List<UUID> entryIds) {
    }

    /** 一天的确认快照。 */
    public record DayFact(LocalDate date, int minutes, UUID submissionId, List<UUID> entryIds) {
    }

    /**
     * 确定性异常：缺勤、未确认、明显偏离、说明缺失、补录。
     *
     * @param code    机器可读编码
     * @param message 中文说明
     * @param date    涉及日期，可为 null
     * @param entryId 涉及条目，可为 null
     */
    public record AnomalyFact(String code, String message, LocalDate date, UUID entryId) {
    }
}
