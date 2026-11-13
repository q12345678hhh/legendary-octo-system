package com.acme.platform.worklog.time;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 日工时提交与审核。
 *
 * <p>提交即冻结快照；重复提交返回同一版本，不产生重复快照。退回只改日状态，
 * 员工修改草稿后再次提交会得到递增版本。
 */
@Service
public class WorklogSubmissionService {

    private final WorklogStore store;
    private final TenantTransactions transactions;
    private final ObjectMapper objectMapper;

    public WorklogSubmissionService(WorklogStore store, TenantTransactions transactions,
                                    ObjectMapper objectMapper) {
        this.store = store;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public record SubmissionView(UUID id, UUID personId, LocalDate workDate, int version,
                                 String status, int totalMinutes, String snapshot,
                                 OffsetDateTime submittedAt) {
    }

    public SubmissionView current(UUID personId, LocalDate date) {
        return transactions.inTenant(context -> store.currentSubmission(personId, date)
                .map(WorklogSubmissionService::toView)
                .orElse(null));
    }

    public SubmissionView find(UUID submissionId) {
        return transactions.inTenant(context -> store.submission(submissionId)
                .map(WorklogSubmissionService::toView)
                .orElseThrow(() -> ApiException.notFound("WORKLOG_SUBMISSION_NOT_FOUND",
                        "工时快照不存在：" + submissionId)));
    }

    public SubmissionView submit(UUID personId, LocalDate date, UUID submittedBy) {
        return transactions.inTenant(context -> {
            WorklogStore.DayState day = store.lockDay(personId, date);
            if ("submitted".equals(day.state()) || "approved".equals(day.state())) {
                return store.currentSubmission(personId, date)
                        .map(WorklogSubmissionService::toView)
                        .orElseThrow(() -> ApiException.conflict("WORKLOG_SUBMISSION_MISSING",
                                "该日已提交但快照缺失，请联系管理员"));
            }
            List<WorklogEntry> entries = store.entries(personId, date);
            if (entries.isEmpty()) {
                throw ApiException.badRequest("WORKLOG_EMPTY_DAY", "当天没有可提交的工时");
            }
            int total = entries.stream().mapToInt(WorklogEntry::durationMinutes).sum();
            int version = store.nextSubmissionVersion(personId, date);
            String snapshot = snapshot(entries);
            UUID submissionId = store.insertSubmission(personId, date, version, snapshot,
                    hash(snapshot), total, submittedBy);
            store.markDaySubmitted(personId, date, version, submissionId);
            return store.submission(submissionId)
                    .map(WorklogSubmissionService::toView)
                    .orElseThrow();
        });
    }

    public SubmissionView review(UUID reviewerPersonId, UUID submissionId, String action,
                                 String comment) {
        return transactions.inTenant(context -> {
            WorklogStore.Submission submission = store.submission(submissionId)
                    .orElseThrow(() -> ApiException.notFound("WORKLOG_SUBMISSION_NOT_FOUND",
                            "工时快照不存在：" + submissionId));
            if (submission.personId().equals(reviewerPersonId)) {
                throw ApiException.forbidden("WORKLOG_SELF_REVIEW", "不能审核自己的工时");
            }
            if (!"submitted".equals(submission.status())) {
                throw ApiException.conflict("WORKLOG_NOT_PENDING",
                        "该提交不在待审核状态：" + submission.status());
            }
            String target = switch (action == null ? "" : action) {
                case "approve" -> "approved";
                case "return" -> "returned";
                default -> throw ApiException.badRequest("WORKLOG_REVIEW_ACTION_INVALID",
                        "审核动作只能是 approve 或 return");
            };
            if ("returned".equals(target) && (comment == null || comment.isBlank())) {
                throw ApiException.badRequest("WORKLOG_RETURN_COMMENT_REQUIRED",
                        "退回必须填写原因");
            }
            store.reviewSubmission(submissionId, target, reviewerPersonId, comment);
            store.updateDayState(submission.personId(), submission.workDate(), target);
            return store.submission(submissionId)
                    .map(WorklogSubmissionService::toView)
                    .orElseThrow();
        });
    }

    public List<SubmissionView> pending(java.util.Collection<UUID> personIds) {
        return transactions.inTenant(context -> store.pendingSubmissions(personIds)
                .stream().map(WorklogSubmissionService::toView).toList());
    }

    private static SubmissionView toView(WorklogStore.Submission submission) {
        return new SubmissionView(submission.id(), submission.personId(), submission.workDate(),
                submission.version(), submission.status(), submission.totalMinutes(),
                submission.snapshot(), submission.submittedAt());
    }

    private String snapshot(List<WorklogEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorklogEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entryId", entry.id());
            row.put("startsAt", entry.startsAt());
            row.put("endsAt", entry.endsAt());
            row.put("minutes", entry.durationMinutes());
            row.put("projectId", entry.projectId());
            row.put("taskId", entry.taskId());
            row.put("description", entry.description());
            row.put("clientEntryId", entry.clientEntryId());
            rows.add(row);
        }
        return objectMapper.writeValueAsString(rows);
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
