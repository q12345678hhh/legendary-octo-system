package com.acme.platform.worklog.report;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.worklog.time.WorklogEntry;
import com.acme.platform.worklog.time.WorklogStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 周报：只引用已提交/已批准的日快照，按项目/任务确定性聚合。
 *
 * <p>Phase 3 不调用模型，员工在聚合结果上补充「问题」和「下周计划」后本人提交；
 * 提交后内容与来源映射冻结，主管退回后只能生成新版本。
 */
@Service
public class WorklogReportService {

    private final WorklogStore store;
    private final TenantTransactions transactions;
    private final ObjectMapper objectMapper;

    public WorklogReportService(WorklogStore store, TenantTransactions transactions,
                                ObjectMapper objectMapper) {
        this.store = store;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public record ReportView(UUID id, UUID personId, LocalDate weekStart, LocalDate weekEnd,
                             int version, String status, Map<String, Object> content,
                             java.time.OffsetDateTime submittedAt) {
    }

    public ReportView find(UUID reportId) {
        return transactions.inTenant(context -> store.report(reportId)
                .map(this::toView)
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND",
                        "周报不存在：" + reportId)));
    }

    public ReportView generate(UUID personId, LocalDate weekStart) {
        return transactions.inTenant(context -> {
            LocalDate start = requireMonday(weekStart);
            LocalDate end = start.plusDays(6);
            List<WorklogStore.Submission> submissions = store.submissionsInWeek(personId, start, end);
            if (submissions.isEmpty()) {
                throw ApiException.badRequest("REPORT_NO_CONFIRMED_TIME",
                        "本周没有已确认工时，不能生成周报");
            }
            WorklogStore.Report latest = store.latestReport(personId, start).orElse(null);
            if (latest != null && "submitted".equals(latest.status())) {
                throw ApiException.conflict("REPORT_ALREADY_SUBMITTED", "周报已提交，不能重新生成");
            }
            if (latest != null && "approved".equals(latest.status())) {
                throw ApiException.conflict("REPORT_ALREADY_APPROVED", "周报已通过，不能重新生成");
            }
            Map<String, Object> content = aggregate(start, end, submissions);
            if (latest != null && "draft".equals(latest.status())) {
                Map<String, Object> existing = parse(latest.content());
                content.put("issues", existing.getOrDefault("issues", ""));
                content.put("nextWeekPlan", existing.getOrDefault("nextWeekPlan", ""));
                store.updateReportContent(latest.id(), objectMapper.writeValueAsString(content));
                return toView(store.report(latest.id()).orElseThrow());
            }
            int version = latest == null ? 1 : latest.version() + 1;
            WorklogStore.Report created = store.insertReport(personId, start, end, version,
                    objectMapper.writeValueAsString(content));
            return toView(created);
        });
    }

    public ReportView updateNotes(UUID personId, LocalDate weekStart, String issues,
                                  String nextWeekPlan) {
        return transactions.inTenant(context -> {
            WorklogStore.Report latest = requireOwnedDraft(personId, weekStart);
            Map<String, Object> content = parse(latest.content());
            content.put("issues", issues == null ? "" : issues.trim());
            content.put("nextWeekPlan", nextWeekPlan == null ? "" : nextWeekPlan.trim());
            store.updateReportContent(latest.id(), objectMapper.writeValueAsString(content));
            return toView(store.report(latest.id()).orElseThrow());
        });
    }

    public ReportView submit(UUID personId, LocalDate weekStart) {
        return transactions.inTenant(context -> {
            WorklogStore.Report latest = requireOwnedDraft(personId, weekStart);
            Map<String, Object> content = parse(latest.content());
            String serialized = objectMapper.writeValueAsString(content);
            store.submitReport(latest.id(), hash(serialized));
            store.replaceReportSources(latest.id(), submissionTotals(content));
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

    private Map<String, Object> aggregate(LocalDate start, LocalDate end,
                                          List<WorklogStore.Submission> submissions) {
        List<Map<String, Object>> days = new ArrayList<>();
        Map<String, Integer> byItem = new LinkedHashMap<>();
        for (WorklogStore.Submission submission : submissions) {
            days.add(Map.of(
                    "date", submission.workDate().toString(),
                    "minutes", submission.totalMinutes(),
                    "submissionId", submission.id().toString()));
            for (WorklogEntry entry : parseSnapshot(submission.snapshot())) {
                String key = entry.projectId() == null ? "unassigned" : "project:" + entry.projectId();
                byItem.merge(key, entry.durationMinutes(), Integer::sum);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        byItem.forEach((key, minutes) -> items.add(Map.of("key", key, "minutes", minutes)));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("weekStart", start.toString());
        content.put("weekEnd", end.toString());
        content.put("days", days);
        content.put("items", items);
        content.put("issues", "");
        content.put("nextWeekPlan", "");
        return content;
    }

    private List<WorklogEntry> parseSnapshot(String snapshot) {
        List<Map<String, Object>> rows = objectMapper.readValue(snapshot,
                new TypeReference<List<Map<String, Object>>>() { });
        List<WorklogEntry> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            entries.add(new WorklogEntry(
                    UUID.fromString(String.valueOf(row.get("entryId"))),
                    null, null, null, null, null,
                    ((Number) row.get("minutes")).intValue(),
                    row.get("projectId") == null ? null : UUID.fromString(String.valueOf(row.get("projectId"))),
                    row.get("taskId") == null ? null : UUID.fromString(String.valueOf(row.get("taskId"))),
                    (String) row.get("description"), "manual",
                    (String) row.get("clientEntryId"), 1));
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String content) {
        return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() { });
    }

    private Map<UUID, Integer> submissionTotals(Map<String, Object> content) {
        Map<UUID, Integer> totals = new LinkedHashMap<>();
        List<Map<String, Object>> days = (List<Map<String, Object>>) content.get("days");
        if (days == null) {
            return totals;
        }
        for (Map<String, Object> day : days) {
            UUID id = UUID.fromString(String.valueOf(day.get("submissionId")));
            totals.put(id, ((Number) day.get("minutes")).intValue());
        }
        return totals;
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
