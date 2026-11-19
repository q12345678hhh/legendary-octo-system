package com.acme.platform.worklog.report.summary;

import com.acme.platform.platform.ApiException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 周报摘要生成编排：AI 失败、未配置或内容全部不可回溯时退回时长模板。
 *
 * <p>本类不访问数据库，也不把异常抛给 Controller；保证生成周报永远可继续编辑与提交。
 */
public class ReportSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportSummaryGenerator.class);

    private final ReportSummarizer summarizer;
    private final ReportSummaryValidator validator;
    private final ReportAiProperties properties;

    public ReportSummaryGenerator(ReportSummarizer summarizer,
                                  ReportSummaryValidator validator,
                                  ReportAiProperties properties) {
        this.summarizer = summarizer;
        this.validator = validator;
        this.properties = properties;
    }

    public Outcome generate(WeekFacts facts) {
        if (!summarizer.configured()) {
            return fallback(facts, "AI_NOT_CONFIGURED", 0);
        }
        ReportSummarizer.SummaryDraft draft;
        try {
            draft = summarizer.summarize(facts);
        } catch (ApiException e) {
            log.warn("周报摘要降级：status={} code={}", e.status(), e.code());
            return fallback(facts, reason(e.code()), 0);
        } catch (RuntimeException e) {
            log.warn("周报摘要降级：{}", e.getClass().getSimpleName());
            return fallback(facts, "AI_FAILED", 0);
        }

        ReportSummaryValidator.ValidatedSummary validated = validator.validate(draft, facts);
        if (validated.summaryPoints().isEmpty()) {
            Outcome fallback = fallback(facts, "ALL_CLAIMS_DROPPED", validated.droppedClaims());
            return new Outcome(fallback.summaryPoints(),
                    validated.highlights().isEmpty() ? fallback.highlights() : validated.highlights(),
                    validated.nextWeekPlan(), false, fallback.provider(), fallback.model(),
                    properties.promptVersion(), "ALL_CLAIMS_DROPPED", validated.droppedClaims());
        }
        return new Outcome(validated.summaryPoints(), validated.highlights(),
                validated.nextWeekPlan(), true, "openai-compatible", properties.model(),
                properties.promptVersion(), null, validated.droppedClaims());
    }

    private Outcome fallback(WeekFacts facts, String reason, int dropped) {
        String hours = String.format(Locale.ROOT, "%.1f", facts.totalMinutes() / 60.0);
        String text = "本周确认工时 " + hours + " 小时，共 " + facts.confirmedDays()
                + " 天，覆盖 " + facts.items().size() + " 个项目或任务。";
        List<java.util.UUID> entryIds = facts.entries().stream()
                .map(WeekFacts.EntryFact::entryId).toList();
        return new Outcome(
                List.of(new ReportSummarizer.SummaryPoint(text, entryIds)),
                List.of(), "", false, "template", "duration-only",
                properties.promptVersion(), reason, dropped);
    }

    private static String reason(String code) {
        return switch (code) {
            case "REPORT_AI_NOT_CONFIGURED" -> "AI_NOT_CONFIGURED";
            case "REPORT_AI_INVALID_OUTPUT" -> "AI_INVALID_OUTPUT";
            default -> "AI_FAILED";
        };
    }

    /**
     * 生成结果。
     *
     * @param aiUsed        摘要是否来自 AI（false 表示时长模板）
     * @param fallbackReason 降级原因；正常生成时为 null
     */
    public record Outcome(List<ReportSummarizer.SummaryPoint> summaryPoints,
                          List<ReportSummarizer.SummaryPoint> highlights,
                          String nextWeekPlan,
                          boolean aiUsed,
                          String provider,
                          String model,
                          String promptVersion,
                          String fallbackReason,
                          int droppedClaims) {
    }
}
