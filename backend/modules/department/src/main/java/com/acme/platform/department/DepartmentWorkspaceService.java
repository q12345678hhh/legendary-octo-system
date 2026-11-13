package com.acme.platform.department;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 部门工作区应用服务。
 *
 * <p>只负责空间、成员投影与部门设置；「谁能看见哪一列」由控制器调用统一授权决策后决定，
 * 本服务不自行判断权限，也不返回任何个人工时正文。
 */
@Service
public class DepartmentWorkspaceService {

    private final DepartmentSpaceStore store;
    private final TenantTransactions transactions;

    public DepartmentWorkspaceService(DepartmentSpaceStore store,
                                      TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public List<DepartmentSpace> spaces(boolean tenantWide, Set<UUID> orgUnitIds) {
        return transactions.inTenant(context -> store.list(tenantWide, orgUnitIds));
    }

    public Optional<Workspace> workspace(UUID orgUnitId) {
        return transactions.inTenant(context -> store.findByUnit(orgUnitId)
                .map(space -> new Workspace(space, store.setting(orgUnitId),
                        store.members(orgUnitId, LocalDate.now()))));
    }

    public Workspace requireWorkspace(UUID orgUnitId) {
        return workspace(orgUnitId).orElseThrow(() ->
                ApiException.notFound("DEPARTMENT_SPACE_NOT_FOUND", "部门空间不存在：" + orgUnitId));
    }

    public DepartmentSpaceStore.Setting updateSetting(UUID orgUnitId, LocalTime dayEndTime,
                                                     int weekday, String updatedBy) {
        if (dayEndTime == null) {
            throw ApiException.badRequest("DAY_END_TIME_REQUIRED", "下班时间不能为空");
        }
        if (weekday < 1 || weekday > 7) {
            throw ApiException.badRequest("WEEKDAY_INVALID", "周报截止星期必须是 1–7");
        }
        return transactions.inTenant(context -> {
            if (store.findByUnit(orgUnitId).isEmpty()) {
                throw ApiException.notFound("DEPARTMENT_SPACE_NOT_FOUND",
                        "部门空间不存在：" + orgUnitId);
            }
            store.upsertSetting(orgUnitId, dayEndTime, weekday,
                    updatedBy == null ? context.subjectId() : updatedBy);
            return store.setting(orgUnitId);
        });
    }

    /**
     * 部门首屏读模型。
     *
     * @param space   空间本身
     * @param setting 部门提醒设置
     * @param members 按主/兼岗、职位与汇报层级排序的成员投影
     */
    public record Workspace(DepartmentSpace space,
                            DepartmentSpaceStore.Setting setting,
                            List<DepartmentSpaceStore.MemberRow> members) {
    }
}
