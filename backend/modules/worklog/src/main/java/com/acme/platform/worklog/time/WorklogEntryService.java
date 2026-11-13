package com.acme.platform.worklog.time;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 工时草稿的录入与校验。
 *
 * <p>所有方法都以调用方传入的 personId 为准，控制器必须从当前主体解析，
 * 不接受请求体里的 personId；本服务不判断查看权限，只判断「是否本人可编辑」。
 */
@Service
public class WorklogEntryService {

    private static final int MAX_DESCRIPTION = 2000;
    private static final int MAX_BACKFILL_DAYS = 7;

    private final WorklogStore store;
    private final TenantTransactions transactions;

    public WorklogEntryService(WorklogStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public record EntryInput(LocalDate workDate,
                             OffsetDateTime startsAt,
                             OffsetDateTime endsAt,
                             Integer durationMinutes,
                             UUID projectId,
                             UUID taskId,
                             String description,
                             String clientEntryId) {
    }

    public List<WorklogEntry> list(UUID personId, LocalDate date) {
        return transactions.inTenant(context -> store.entries(personId, date));
    }

    public WorklogEntry create(UUID personId, UUID employmentId, EntryInput input) {
        return transactions.inTenant(context -> {
            LocalDate date = requireEditableDate(input.workDate());
            requireEditableDay(personId, date);
            if (input.clientEntryId() == null || input.clientEntryId().isBlank()) {
                throw ApiException.badRequest("CLIENT_ENTRY_ID_REQUIRED",
                        "缺少客户端条目标识，无法保证离线补录幂等");
            }
            WorklogEntry existing = store.entries(personId, date).stream()
                    .filter(entry -> input.clientEntryId().equals(entry.clientEntryId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                return existing;
            }
            int minutes = resolveMinutes(input);
            String description = requireDescription(input.description());
            validateOverlap(personId, date, input.startsAt(), input.endsAt(), null);
            validateDailyTotal(personId, date, minutes, null);
            UUID id = store.insertEntry(personId, employmentId, date, input.startsAt(),
                    input.endsAt(), minutes, input.projectId(), input.taskId(), description,
                    input.clientEntryId(), 1);
            return requireEntry(id);
        });
    }

    public WorklogEntry update(UUID personId, UUID entryId, EntryInput input) {
        return transactions.inTenant(context -> {
            WorklogEntry current = requireOwnedEntry(personId, entryId);
            LocalDate date = requireEditableDate(input.workDate() == null
                    ? current.workDate() : input.workDate());
            if (!date.equals(current.workDate())) {
                throw ApiException.badRequest("WORK_DATE_CHANGE_UNSUPPORTED",
                        "不支持直接改日期，请删除后在新日期重新录入");
            }
            requireEditableDay(personId, date);
            int minutes = resolveMinutes(input);
            String description = requireDescription(input.description());
            validateOverlap(personId, date, input.startsAt(), input.endsAt(), entryId);
            validateDailyTotal(personId, date, minutes, entryId);
            store.updateEntry(entryId, input.startsAt(), input.endsAt(), minutes,
                    input.projectId(), input.taskId(), description);
            return requireEntry(entryId);
        });
    }

    public void delete(UUID personId, UUID entryId) {
        transactions.inTenant(context -> {
            WorklogEntry current = requireOwnedEntry(personId, entryId);
            requireEditableDay(personId, current.workDate());
            store.softDelete(entryId);
            return null;
        });
    }

    public WorklogEntry merge(UUID personId, List<UUID> entryIds, String description) {
        return transactions.inTenant(context -> {
            if (entryIds == null || entryIds.size() < 2) {
                throw ApiException.badRequest("MERGE_REQUIRES_TWO",
                        "合并至少需要两条工时");
            }
            Set<UUID> unique = new LinkedHashSet<>(entryIds);
            if (unique.size() != entryIds.size()) {
                throw ApiException.badRequest("MERGE_DUPLICATE", "合并列表包含重复条目");
            }
            List<WorklogEntry> entries = new ArrayList<>();
            for (UUID id : unique) {
                entries.add(requireOwnedEntry(personId, id));
            }
            LocalDate date = entries.get(0).workDate();
            if (entries.stream().anyMatch(entry -> !entry.workDate().equals(date))) {
                throw ApiException.badRequest("MERGE_CROSS_DAY", "只能合并同一天的工时");
            }
            requireEditableDay(personId, date);
            int total = entries.stream().mapToInt(WorklogEntry::durationMinutes).sum();
            validateDailyTotal(personId, date, total, null);
            String summary = description == null || description.isBlank()
                    ? entries.stream().map(WorklogEntry::description)
                    .filter(Objects::nonNull).distinct()
                    .reduce((left, right) -> left + "；" + right).orElse("合并工时")
                    : requireDescription(description);
            WorklogEntry first = entries.get(0);
            UUID newId = store.insertEntry(personId, first.employmentId(), date,
                    null, null, total, first.projectId(), first.taskId(), summary,
                    "merge-" + UUID.randomUUID(), first.draftVersion());
            entries.forEach(entry -> store.softDelete(entry.id()));
            return requireEntry(newId);
        });
    }

    public List<WorklogEntry> split(UUID personId, UUID entryId, int firstMinutes) {
        return transactions.inTenant(context -> {
            WorklogEntry entry = requireOwnedEntry(personId, entryId);
            requireEditableDay(personId, entry.workDate());
            if (firstMinutes < 1 || firstMinutes >= entry.durationMinutes()) {
                throw ApiException.badRequest("SPLIT_INVALID",
                        "拆分分钟数必须大于 0 且小于原时长");
            }
            int second = entry.durationMinutes() - firstMinutes;
            UUID firstId = store.insertEntry(personId, entry.employmentId(), entry.workDate(),
                    entry.startsAt(), null, firstMinutes, entry.projectId(), entry.taskId(),
                    entry.description(), "split-" + UUID.randomUUID(), entry.draftVersion());
            UUID secondId = store.insertEntry(personId, entry.employmentId(), entry.workDate(),
                    null, entry.endsAt(), second, entry.projectId(), entry.taskId(),
                    entry.description(), "split-" + UUID.randomUUID(), entry.draftVersion());
            store.softDelete(entryId);
            return List.of(requireEntry(firstId), requireEntry(secondId));
        });
    }

    private WorklogEntry requireOwnedEntry(UUID personId, UUID entryId) {
        WorklogEntry entry = store.entry(entryId).orElseThrow(() ->
                ApiException.notFound("WORKLOG_ENTRY_NOT_FOUND", "工时条目不存在：" + entryId));
        if (!entry.personId().equals(personId)) {
            throw ApiException.forbidden("WORKLOG_NOT_OWNER", "只能修改自己的工时");
        }
        return entry;
    }

    private WorklogEntry requireEntry(UUID id) {
        return store.entry(id).orElseThrow(() ->
                ApiException.notFound("WORKLOG_ENTRY_NOT_FOUND", "工时条目不存在：" + id));
    }

    private LocalDate requireEditableDate(LocalDate date) {
        if (date == null) {
            throw ApiException.badRequest("WORK_DATE_REQUIRED", "缺少工作日期");
        }
        LocalDate today = LocalDate.now();
        if (date.isAfter(today)) {
            throw ApiException.badRequest("WORK_DATE_FUTURE", "不能录入未来日期的工时");
        }
        if (date.isBefore(today.minusDays(MAX_BACKFILL_DAYS))) {
            throw ApiException.badRequest("WORK_DATE_TOO_OLD",
                    "最多允许补录 " + MAX_BACKFILL_DAYS + " 天内的工时");
        }
        return date;
    }

    private void requireEditableDay(UUID personId, LocalDate date) {
        store.day(personId, date).ifPresent(day -> {
            if (!"draft".equals(day.state()) && !"returned".equals(day.state())) {
                throw ApiException.conflict("WORKLOG_DAY_LOCKED",
                        "该日工时已提交或已通过，需退回后才能修改");
            }
        });
    }

    private int resolveMinutes(EntryInput input) {
        if (input.startsAt() != null && input.endsAt() != null) {
            if (!input.endsAt().isAfter(input.startsAt())) {
                throw ApiException.badRequest("WORKLOG_TIME_RANGE_INVALID", "结束时间必须晚于开始时间");
            }
            long between = Duration.between(input.startsAt(), input.endsAt()).toMinutes();
            if (between < 1 || between > 1440) {
                throw ApiException.badRequest("WORKLOG_DURATION_INVALID", "单条工时必须在 1–1440 分钟");
            }
            if (input.durationMinutes() != null && input.durationMinutes() != between) {
                throw ApiException.badRequest("WORKLOG_DURATION_MISMATCH",
                        "时长与起止时间不一致");
            }
            return (int) between;
        }
        if (input.durationMinutes() == null
                || input.durationMinutes() < 1 || input.durationMinutes() > 1440) {
            throw ApiException.badRequest("WORKLOG_DURATION_INVALID", "单条工时必须在 1–1440 分钟");
        }
        return input.durationMinutes();
    }

    private String requireDescription(String description) {
        String value = description == null ? "" : description.trim();
        if (value.length() > MAX_DESCRIPTION) {
            throw ApiException.badRequest("WORKLOG_DESCRIPTION_TOO_LONG",
                    "工作说明不能超过 " + MAX_DESCRIPTION + " 个字符");
        }
        return value;
    }

    private void validateOverlap(UUID personId, LocalDate date, OffsetDateTime start,
                                 OffsetDateTime end, UUID excludingId) {
        if (start == null || end == null) {
            return;
        }
        List<WorklogEntry> overlaps = store.overlapping(personId, date, start, end, excludingId);
        if (!overlaps.isEmpty()) {
            throw ApiException.conflict("WORKLOG_OVERLAP",
                    "与其他工时重叠：" + overlaps.get(0).id());
        }
    }

    private void validateDailyTotal(UUID personId, LocalDate date, int minutes, UUID excludingId) {
        int existing = excludingId == null ? store.totalMinutes(personId, date, null)
                : store.totalMinutes(personId, date, excludingId);
        int max = store.setting().maxDailyMinutes();
        if (existing + minutes > max) {
            throw ApiException.badRequest("WORKLOG_DAILY_LIMIT",
                    "当日累计工时不能超过 " + max + " 分钟");
        }
    }
}
