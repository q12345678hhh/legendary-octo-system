package com.acme.platform.department.insight;

import com.acme.platform.department.DepartmentSpaceStore;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 权限感知洞察：固定维度统计 + k-匿名阈值抑制。
 *
 * <p>阈值来自 {@code worklog_setting.min_group_size}（最低 5，只能提高）。
 * 任何口径下贡献人数低于阈值时整格抑制，不返回数量、均值或排名；
 * 草稿与返回中的工时永不参与统计。
 */
@Service
public class InsightService {

    private static final Set<String> TASK_STATUSES = Set.of("todo", "doing", "done");

    private final InsightStore store;
    private final DepartmentSpaceStore spaceStore;
    private final TenantTransactions transactions;

    public InsightService(InsightStore store, DepartmentSpaceStore spaceStore,
                          TenantTransactions transactions) {
        this.store = store;
        this.spaceStore = spaceStore;
        this.transactions = transactions;
    }

    public record TaskStatusCount(String status, int count) {
    }

    public record ReportCounts(int draft, int submitted, int approved, int returned) {
    }

    public record WeekRow(LocalDate weekStart, Integer submittedPersonDays, boolean suppressed) {
    }

    public record Overview(UUID orgUnitId, LocalDate from, LocalDate to, int threshold,
                           boolean suppressed, Integer memberCount, Double submissionRate,
                           Integer submittedPersonDays, Integer expectedPersonDays,
                           Long totalSubmittedMinutes, Integer avgMinutesPerPerson,
                           List<TaskStatusCount> taskStatus, Integer overdueTasks,
                           ReportCounts weeklyReports, List<WeekRow> weeks) {
    }

    /** 部门口径：人员集合来自部门空间成员（含兼岗去重）。 */
    public Overview overview(UUID orgUnitId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        if (orgUnitId == null) {
            throw ApiException.badRequest("INSIGHT_ORG_UNIT_REQUIRED", "缺少部门标识");
        }
        return transactions.inTenant(context -> {
            int threshold = store.setting().minGroupSize();
            return buildOverview(orgUnitId, departmentPeople(orgUnitId), from, to, threshold);
        });
    }

    /** 授权范围口径：人员集合由调用方从 {@code AccessScope.personIds} 下推。 */
    public Overview overviewForPeople(UUID orgUnitId, Set<UUID> scopePersonIds, LocalDate from,
                                      LocalDate to) {
        validateRange(from, to);
        if (scopePersonIds == null || scopePersonIds.isEmpty()) {
            throw ApiException.forbidden("INSIGHT_SCOPE_EMPTY", "当前范围没有可统计的人员");
        }
        return transactions.inTenant(context -> {
            int threshold = store.setting().minGroupSize();
            return buildOverview(orgUnitId, List.copyOf(scopePersonIds), from, to, threshold);
        });
    }

    /**
     * 下钻：群组仍须满足阈值；返回后由控制器逐人做 {@code worklog.time.read} 二次鉴权。
     */
    public List<InsightStore.PersonRow> drilldown(UUID orgUnitId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        if (orgUnitId == null) {
            throw ApiException.badRequest("INSIGHT_ORG_UNIT_REQUIRED", "缺少部门标识");
        }
        return transactions.inTenant(context -> {
            int threshold = store.setting().minGroupSize();
            List<UUID> personIds = departmentPeople(orgUnitId);
            requireGroup(personIds, orgUnitId, from, to, threshold);
            return store.people(personIds, from, to);
        });
    }

    /** 授权范围口径的下钻，阈值判定与部门口径一致。 */
    public List<InsightStore.PersonRow> drilldownForPeople(UUID orgUnitId, Set<UUID> scopePersonIds,
                                                           LocalDate from, LocalDate to) {
        validateRange(from, to);
        if (scopePersonIds == null || scopePersonIds.isEmpty()) {
            throw ApiException.forbidden("INSIGHT_SCOPE_EMPTY", "当前范围没有可统计的人员");
        }
        return transactions.inTenant(context -> {
            int threshold = store.setting().minGroupSize();
            List<UUID> personIds = List.copyOf(scopePersonIds);
            requireGroup(personIds, orgUnitId, from, to, threshold);
            return store.people(personIds, from, to);
        });
    }

    /** 当前租户的最小群组阈值；用于控制器对「二次鉴权后剩余人群」再判定一次。 */
    public int threshold() {
        return transactions.inTenant(context -> store.setting().minGroupSize());
    }

    public InsightStore.Setting settings() {
        return transactions.inTenant(context -> store.setting());
    }

    public InsightStore.Setting updateMinGroupSize(Integer minGroupSize, String updatedBy) {
        if (minGroupSize == null || minGroupSize < 5 || minGroupSize > 100) {
            throw ApiException.badRequest("INSIGHT_THRESHOLD_INVALID",
                    "最小群组阈值必须在 5–100 之间");
        }
        return transactions.inTenant(context -> {
            InsightStore.Setting current = store.setting();
            if (minGroupSize < current.minGroupSize()) {
                throw ApiException.badRequest("INSIGHT_THRESHOLD_DECREASE",
                        "最小群组阈值只能提高，当前为 " + current.minGroupSize());
            }
            store.updateMinGroupSize(minGroupSize,
                    updatedBy == null ? context.subjectId() : updatedBy);
            return store.setting();
        });
    }

    private Overview buildOverview(UUID orgUnitId, List<UUID> personIds, LocalDate from,
                                   LocalDate to, int threshold) {
        if (personIds.size() < threshold) {
            return suppressed(orgUnitId, from, to, threshold);
        }
        InsightStore.Aggregate aggregate = store.aggregate(personIds, orgUnitId, from, to);
        if (aggregate.contributors() < threshold) {
            return suppressed(orgUnitId, from, to, threshold);
        }

        int businessDays = businessDays(from, to);
        int expected = personIds.size() * businessDays;
        double rate = expected == 0 ? 0.0
                : Math.min(1.0, (double) aggregate.submittedDays() / expected);
        List<TaskStatusCount> statuses = TASK_STATUSES.stream()
                .sorted()
                .map(status -> new TaskStatusCount(status,
                        aggregate.taskStatus().getOrDefault(status, 0)))
                .toList();
        ReportCounts reports = new ReportCounts(
                aggregate.reportStatus().getOrDefault("draft", 0),
                aggregate.reportStatus().getOrDefault("submitted", 0),
                aggregate.reportStatus().getOrDefault("approved", 0),
                aggregate.reportStatus().getOrDefault("returned", 0));
        List<WeekRow> weeks = aggregate.weeks().stream()
                .map(week -> week.contributors() < threshold
                        ? new WeekRow(week.weekStart(), null, true)
                        : new WeekRow(week.weekStart(), week.submittedDays(), false))
                .toList();

        return new Overview(orgUnitId, from, to, threshold, false, personIds.size(),
                round(rate), aggregate.submittedDays(), expected, aggregate.totalMinutes(),
                (int) (aggregate.totalMinutes() / personIds.size()), statuses,
                aggregate.overdueTasks(), reports, weeks);
    }

    private void requireGroup(List<UUID> personIds, UUID orgUnitId, LocalDate from, LocalDate to,
                              int threshold) {
        if (personIds.size() < threshold) {
            throw suppressionError(threshold);
        }
        if (store.aggregate(personIds, orgUnitId, from, to).contributors() < threshold) {
            throw suppressionError(threshold);
        }
    }

    private List<UUID> departmentPeople(UUID orgUnitId) {
        return distinctPeople(spaceStore.members(orgUnitId, LocalDate.now()));
    }

    private static Overview suppressed(UUID orgUnitId, LocalDate from, LocalDate to,
                                       int threshold) {
        return new Overview(orgUnitId, from, to, threshold, true, null, null, null, null,
                null, null, List.of(), null, null, List.of());
    }

    private static ApiException suppressionError(int threshold) {
        return ApiException.badRequest("INSIGHT_GROUP_TOO_SMALL",
                "群组人数低于最小阈值 " + threshold + "，已抑制明细");
    }

    private static List<UUID> distinctPeople(List<DepartmentSpaceStore.MemberRow> members) {
        Set<UUID> people = new LinkedHashSet<>();
        for (DepartmentSpaceStore.MemberRow member : members) {
            if (member.personId() != null) {
                people.add(member.personId());
            }
        }
        return List.copyOf(people);
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.badRequest("INSIGHT_RANGE_REQUIRED", "缺少统计起止日期");
        }
        if (from.isAfter(to)) {
            throw ApiException.badRequest("INSIGHT_RANGE_INVALID", "开始日期不能晚于结束日期");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw ApiException.badRequest("INSIGHT_RANGE_TOO_LONG", "统计区间不能超过一年");
        }
    }

    private static int businessDays(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
