package com.acme.platform.worklog.report.summary;

import java.util.List;
import java.util.UUID;

/**
 * 周报摘要端口。
 *
 * <p>实现只能基于 {@link WeekFacts} 组织文字，不得引入外部事实；
 * 每条摘要与产出都必须给出本周工时条目引用。
 */
public interface ReportSummarizer {

    boolean configured();

    SummaryDraft summarize(WeekFacts facts);

    record SummaryDraft(List<SummaryPoint> summaryPoints, List<SummaryPoint> highlights,
                        String nextWeekPlan) {
    }

    record SummaryPoint(String text, List<UUID> entryIds) {
    }
}
