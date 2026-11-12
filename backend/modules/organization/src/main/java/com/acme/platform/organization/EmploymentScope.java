package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 当前主体的组织范围快照：由 {@link OrganizationScopeQueries} 按「当前生效」口径计算。
 *
 * <p>只读，不落库；每次判定重新计算，调动、离职、委派与老板角色变更后自然生效。
 */
public record EmploymentScope(UUID personId,
                              UUID employmentId,
                              UUID primaryOrgUnitId,
                              UUID legalEntityId,
                              boolean owner,
                              boolean trustee,
                              java.util.Set<UUID> directReportPersonIds,
                              java.util.Set<UUID> chainPersonIds,
                              java.util.Set<UUID> delegatedOrgUnitIds) {

    public EmploymentScope {
        directReportPersonIds = directReportPersonIds == null
                ? java.util.Set.of() : java.util.Set.copyOf(directReportPersonIds);
        chainPersonIds = chainPersonIds == null
                ? java.util.Set.of() : java.util.Set.copyOf(chainPersonIds);
        delegatedOrgUnitIds = delegatedOrgUnitIds == null
                ? java.util.Set.of() : java.util.Set.copyOf(delegatedOrgUnitIds);
    }

    public boolean hasReports() {
        return !directReportPersonIds.isEmpty() || !chainPersonIds.isEmpty();
    }

    public boolean isBoss() {
        return owner || trustee;
    }
}
