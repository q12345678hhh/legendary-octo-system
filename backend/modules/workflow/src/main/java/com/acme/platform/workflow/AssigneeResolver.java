package com.acme.platform.workflow;

import com.acme.platform.authorization.RoleMembershipQueries;
import com.acme.platform.organization.OrganizationScopeQueries;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审批人解析：person / position / unit_head / reporting_chain / role / business_owner。
 *
 * <p>「部门负责人」在 Phase 3 按「发起人当前实线主管」实现（组织暂无部门负责人主数据字段）；
 * 解析结果为空时由调用方写异常，绝不自动通过。
 */
@Component
public class AssigneeResolver {

    private final OrganizationScopeQueries organization;
    private final RoleMembershipQueries roles;

    public AssigneeResolver(OrganizationScopeQueries organization, RoleMembershipQueries roles) {
        this.organization = organization;
        this.roles = roles;
    }

    public List<UUID> resolve(WorkflowSchema.Node node, UUID initiatorPersonId, LocalDate today) {
        if (node == null || node.assigneeKind() == null) {
            return List.of();
        }
        List<UUID> resolved = switch (node.assigneeKind()) {
            case "person" -> node.assigneePersonId() != null
                    && organization.activePerson(node.assigneePersonId(), today)
                    ? List.of(node.assigneePersonId()) : List.of();
            case "position" -> organization.personIdsAtPosition(node.positionId(), today);
            case "unit_head" -> organization.managerPersonOf(initiatorPersonId, today)
                    .map(List::of).orElse(List.of());
            case "reporting_chain" -> organization.managerChainPersonIds(initiatorPersonId,
                    node.chainDepth(), today);
            case "role" -> roles.personIdsWithRole(node.roleCode(), today);
            case "business_owner" -> organization.ownerPersonIds(today);
            default -> List.of();
        };
        Set<UUID> distinct = new LinkedHashSet<>(resolved);
        distinct.remove(null);
        return new ArrayList<>(distinct);
    }
}
