package com.acme.platform.worklog.report;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.worklog.report.summary.ReportFactsBuilder;
import com.acme.platform.worklog.report.summary.ReportSummarizer;
import com.acme.platform.worklog.report.summary.ReportSummaryGenerator;
import com.acme.platform.worklog.report.summary.WeekFacts;
import com.acme.platform.worklog.time.WorklogStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 周报：事实只来自已提交/已批准的日快照，文字由 AI 组织，失败时降级为时长模板。
 *
 * <p>生成分三段：事务内读事实 → 事务外调用模型 → 事务内写草稿。
 * 提交后内容与来源映射一起冻结；退回后重新生成会保留员工已编辑的摘要与计划。
 */
@Service
public class WorklogReportService {

    private final WorklogStore store;
    private final TenantTransactions transactions;
    private final ObjectMapper objectMapper;
    private final ReportFactsBuilder factsBuilder;
    private final ReportSummaryGenerator summaryGenerator;

    public WorklogReportService(WorklogStore store, TenantTransactions transactions,
                                ObjectMapper objectMapper, ReportFactsBuilder factsBuilder,
                                ReportSummaryGenerator summaryGenerator) {
        this.store = store;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.factsBuilder = factsBuilder;
        this.summaryGenerator = summaryGenerator;
    }

    public record ReportView(UUID id, UUID personId, LocalDate weekStart, LocalDate weekEnd,
                             int version, String status, Map<String, Object> content,
                             OffsetDateTime submittedAt) {
    }

    /** 编辑入参：null 表示该字段不动，避免前端漏传时被清空。 */
    public record NotesPatch(String summary, String issues, String nextWeekPlan) {
    }

    private record GenerationPlan(LocalDate start, LocalDate end, WeekFacts facts) {
    }

    public ReportView find(UUID reportId) {
        return transactions.inTenant(context -> store.report(reportId)
                .map(this::toView)
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND",
                        "周报不存在：" + reportId)));
    }

    public ReportView generate(UUID personId, LocalDate weekStart) {
        // 第一段：事务内读取事实，不在这里发外部请求。
        GenerationPlan plan = transactions.inTenant(context -> {
            LocalDate start = requireMonday(weekStart);
            LocalDate end = start.plusDays(6);
            List<WorklogStore.Submission> submissions = store.submissionsInWeek(personId, start, end);
            if (submissions.isEmpty()) {
                throw ApiException.badRequest("REPORT_NO_CONFIRMED_TIME",
                        "本周没有已确认工时，不能生成周报");
            }
            WorklogStore.Report latest = store.latestReport(personId, start).orElse(null);
            rejectFrozen(latest);
            int maxDaily = store.setting().maxDailyMinutes();
            WeekFacts facts = factsBuilder.build(submissions,
                    store.dayStatesInRange(personId, start, end),
                    start, end, maxDaily, LocalDate.now());
            return new GenerationPlan(start, end, facts);
        });

        // 第二段：无事务调用模型；任何失败都在 generator 内降级。
        ReportSummaryGenerator.Outcome outcome = summaryGenerator.generate(plan.facts());

        // 第三段：事务内写草稿，重新检查状态避免并发提交被覆盖。
        return transactions.inTenant(context -> {
            WorklogStore.Report latest = store.latestReport(personId, plan.start()).orElse(null);
            rejectFrozen(latest);
            Map<String, Object> content = buildContent(plan.facts(), outcome);
            if (latest != null && "draft".equals(latest.status())) {
                Map<String, Object> existing = parse(latest.content());
                content.put("issues", existing.getOrDefault("issues", ""));
                if ("employee".equals(existing.get("summarySource"))) {
                    content.put("summary", existing.getOrDefault("summary", ""));
                    content.put("summaryPoints", existing.getOrDefault("summaryPoints", List.of()));
                    content.put("summarySource", "employee");
                }
                if ("employee".equals(existing.get("nextWeekPlanSource"))) {
                    content.put("nextWeekPlan", existing.getOrDefault("nextWeekPlan", ""));
                    content.put("nextWeekPlanSource", "employee");
                }
                store.updateReportContent(latest.id(), objectMapper.writeValueAsString(content));
                return toView(store.report(latest.id()).orElseThrow());
            }
            int version = latest == null ? 1 : latest.version() + 1;
            try {
                WorklogStore.Report created = store.insertReport(personId, plan.start(),
                        plan.end(), version, objectMapper.writeValueAsString(content));
                return toView(created);
            } catch (DataIntegrityViolationException e) {
                throw ApiException.conflict("REPORT_GENERATION_CONFLICT",
                        "周报已被其他操作更新，请刷新后重试");
            }
        });
    }

    public ReportView updateNotes(UUID personId, LocalDate weekStart, NotesPatch patch) {
        return transactions.inTenant(context -> {
            WorklogStore.Report latest = requireOwnedDraft(personId, weekStart);
            Map<String, Object> content = parse(latest.content());
            if (patch.summary() != null) {
                content.put("summary", patch.summary().trim());
                content.put("summaryPoints", List.of());
                content.put("summarySource", "employee");
            }
            if (patch.issues() != null) {
                content.put("issues", patch.issues().trim());
            }
            if (patch.nextWeekPlan() != null) {
                String plan = patch.nextWeekPlan().trim();
                content.put("nextWeekPlan", plan);
                content.put("nextWeekPlanSource", plan.isEmpty() ? "empty" : "employee");
            }
            store.updateReportContent(latest.id(), objectMapper.writeValueAsString(content));
            return toView(store.report(latest.id()).orElseThrow());
        });
    }

    public ReportView submit(UUID personId, LocalDate weekStart) {
        return transactions.inTenant(context -> {
            WorklogStore.Report latest = requireOwnedDraft(personId, weekStart);
            Map<String, Object> content = parse(latest.content());
            String serialized = objectMapper.writeValueAsString(content);
            // 先写来源映射（父周报仍为 draft），再冻结周报，避免被 V20 触发器拦下。
            store.replaceReportSources(latest.id(), reportSources(content));
            store.submitReport(latest.id(), hash(serialized));
            return toView(store.report(latest.id()).orElseThrow());
        });
    }

    public ReportView review(UUID reviewerPersonId, UUID reportId, String action, String comment) {
        return transactions.inTenant(context -> {
            WorklogStore.Report report = store.report(reportId)
                    .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND",
                            "周报不存在：" + reportId));
            if (report.personId().equals(reviewerPersonId)) {
                throw ApiException.forbidden("REPORT_SELF_REVIEW", "不能审核自己的周报");
            }
            if (!"submitted".equals(report.status())) {
                throw ApiException.conflict("REPORT_NOT_PENDING",
                        "该周报不在待审核状态：" + report.status());
            }
            String target = switch (action == null ? "" : action) {
                case "approve" -> "approved";
                case "return" -> "returned";
                default -> throw ApiException.badRequest("REPORT_REVIEW_ACTION_INVALID",
                        "审核动作只能是 approve 或 return");
            };
            if ("returned".equals(target) && (comment == null || comment.isBlank())) {
                throw ApiException.badRequest("REPORT_RETURN_COMMENT_REQUIRED", "退回必须填写原因");
            }
            store.reviewReport(reportId, target, reviewerPersonId, comment);
            return toView(store.report(reportId).orElseThrow());
        });
    }

    public List<ReportView> pending(java.util.Collection<UUID> personIds) {
        return transactions.inTenant(context -> store.pendingReports(personIds)
                .stream().map(this::toView).toList());
    }

    public ReportView latest(UUID personId, LocalDate weekStart) {
        return transactions.inTenant(context -> store.latestReport(personId, requireMonday(weekStart))
                .map(this::toView).orElse(null));
    }

    /** 本人查看该周全部版本，仅供追溯；主管仍只能看到 submitted 列表。 */
    public List<ReportView> versions(UUID personId, LocalDate weekStart) {
        return transactions.inTenant(context -> store.reportsForWeek(personId, requireMonday(weekStart))
                .stream().map(this::toView).toList());
    }

    private WorklogStore.Report requireOwnedDraft(UUID personId, LocalDate weekStart) {
        WorklogStore.Report latest = store.latestReport(personId, requireMonday(weekStart))
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND", "周报不存在"));
        if (!latest.personId().equals(personId)) {
            throw ApiException.forbidden("REPORT_NOT_OWNER", "只能操作自己的周报");
        }
        if (!"draft".equals(latest.status())) {
            throw ApiException.conflict("REPORT_NOT_DRAFT", "只有草稿周报可以修改或提交");
        }
        return latest;
    }

    private static void rejectFrozen(WorklogStore.Report latest) {
        if (latest == null) {
            return;
        }
        if ("submitted".equals(latest.status())) {
            throw ApiException.conflict("REPORT_ALREADY_SUBMITTED", "周报已提交，不能重新生成");
        }
        if ("approved".equals(latest.status())) {
            throw ApiException.conflict("REPORT_ALREADY_APPROVED", "周报已通过，不能重新生成");
        }
    }

    private Map<String, Object> buildContent(WeekFacts facts,
                                             ReportSummaryGenerator.Outcome outcome) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", 2);
        content.put("weekStart", facts.weekStart().toString());
        content.put("weekEnd", facts.weekEnd().toString());

        List<Map<String, Object>> days = new ArrayList<>();
        for (WeekFacts.DayFact day : facts.days()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.date().toString());
            row.put("minutes", day.minutes());
            row.put("submissionId", day.submissionId().toString());
            row.put("entryIds", day.entryIds().stream().map(UUID::toString).toList());
            days.add(row);
        }
        content.put("days", days);
        content.put("entries", entries(facts));
        content.put("items", items(facts));

        content.put("summary", summaryText(outcome));
        content.put("summaryPoints", points(outcome.summaryPoints()));
        content.put("summarySource", outcome.aiUsed() ? "ai" : "fallback");
        content.put("highlights", points(outcome.highlights()));
        content.put("anomalies", anomalies(facts));
        content.put("nextWeekPlan", outcome.nextWeekPlan() == null ? "" : outcome.nextWeekPlan());
        content.put("nextWeekPlanSource", nextWeekPlanSource(outcome));
        content.put("issues", "");

        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("mode", outcome.aiUsed() ? "ai" : "fallback");
        generation.put("provider", outcome.provider());
        generation.put("model", outcome.model());
        generation.put("promptVersion", outcome.promptVersion());
        generation.put("generatedAt", OffsetDateTime.now().toString());
        generation.put("fallbackReason", outcome.fallbackReason());
        generation.put("droppedClaims", outcome.droppedClaims());
        generation.put("truncated", facts.truncated());
        content.put("generation", generation);
        return content;
    }

    private static List<Map<String, Object>> entries(WeekFacts facts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeekFacts.EntryFact entry : facts.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entryId", entry.entryId().toString());
            row.put("date", entry.date().toString());
            row.put("minutes", entry.minutes());
            row.put("projectId", entry.projectId() == null ? null : entry.projectId().toString());
            row.put("taskId", entry.taskId() == null ? null : entry.taskId().toString());
            row.put("description", entry.description());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> items(WeekFacts facts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeekFacts.ItemFact item : facts.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", item.key());
            row.put("minutes", item.minutes());
            row.put("entryIds", item.entryIds().stream().map(UUID::toString).toList());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> anomalies(WeekFacts facts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeekFacts.AnomalyFact anomaly : facts.anomalies()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", anomaly.code());
            row.put("message", anomaly.message());
            row.put("date", anomaly.date() == null ? null : anomaly.date().toString());
            row.put("entryId", anomaly.entryId() == null ? null : anomaly.entryId().toString());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> points(List<ReportSummarizer.SummaryPoint> points) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportSummarizer.SummaryPoint point : points) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", point.text());
            row.put("entryIds", point.entryIds().stream().map(UUID::toString).toList());
            rows.add(row);
        }
        return rows;
    }

    private static String summaryText(ReportSummaryGenerator.Outcome outcome) {
        return outcome.summaryPoints().stream()
                .map(ReportSummarizer.SummaryPoint::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static String nextWeekPlanSource(ReportSummaryGenerator.Outcome outcome) {
        if (outcome.nextWeekPlan() == null || outcome.nextWeekPlan().isBlank()) {
            return "empty";
        }
        return "ai-suggested";
    }

    /**
     * 来源映射：每个日快照写 total、项目聚合与条目明细三级，支撑任意数字回溯。
     * 兼容旧版 content（无 entries/entryIds 时只写 total）。
     */
    @SuppressWarnings("unchecked")
    private static List<WorklogStore.ReportSourceRow> reportSources(Map<String, Object> content) {
        List<WorklogStore.ReportSourceRow> rows = new ArrayList<>();
        List<Map<String, Object>> days = (List<Map<String, Object>>) content.get("days");
        if (days == null) {
            return rows;
        }
        Map<String, Map<String, Object>> entryById = new LinkedHashMap<>();
        List<Map<String, Object>> entries = (List<Map<String, Object>>) content.get("entries");
        if (entries != null) {
            for (Map<String, Object> entry : entries) {
                entryById.put(String.valueOf(entry.get("entryId")), entry);
            }
        }
        for (Map<String, Object> day : days) {
            UUID submissionId = UUID.fromString(String.valueOf(day.get("submissionId")));
            int minutes = ((Number) day.get("minutes")).intValue();
            rows.add(new WorklogStore.ReportSourceRow(submissionId, "total", minutes));
            List<Object> entryIds = (List<Object>) day.get("entryIds");
            if (entryIds == null) {
                continue;
            }
            Map<String, Integer> byProject = new LinkedHashMap<>();
            for (Object rawId : entryIds) {
                String entryId = String.valueOf(rawId);
                Map<String, Object> entry = entryById.get(entryId);
                if (entry == null) {
                    continue;
                }
                int entryMinutes = ((Number) entry.get("minutes")).intValue();
                rows.add(new WorklogStore.ReportSourceRow(submissionId, "entry:" + entryId,
                        entryMinutes));
                String key = entry.get("projectId") == null
                        ? "unassigned" : "project:" + entry.get("projectId");
                byProject.merge(key, entryMinutes, Integer::sum);
            }
            byProject.forEach((key, projectMinutes) ->
                    rows.add(new WorklogStore.ReportSourceRow(submissionId, key, projectMinutes)));
        }
        return rows;
    }

    private Map<String, Object> parse(String content) {
        return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() { });
    }

    private ReportView toView(WorklogStore.Report report) {
        return new ReportView(report.id(), report.personId(), report.weekStart(),
                report.weekEnd(), report.version(), report.status(),
                objectMapper.readValue(report.content(),
                        new TypeReference<Map<String, Object>>() { }),
                report.submittedAt());
    }

    private static LocalDate requireMonday(LocalDate weekStart) {
        if (weekStart == null || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.badRequest("REPORT_WEEK_START_INVALID", "周报起始日期必须是周一");
        }
        return weekStart;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
