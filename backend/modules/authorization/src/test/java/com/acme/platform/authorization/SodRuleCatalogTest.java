package com.acme.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SodRuleCatalogTest {

    private final UUID actor = UUID.randomUUID();

    @Test
    void payrollRolesAreMutuallyExclusiveOnlyForTheirOwnPermissions() {
        Set<String> roles = Set.of(SystemRoleCatalog.PAYROLL_PREPARER,
                SystemRoleCatalog.PAYROLL_APPROVER);

        assertThat(SodRuleCatalog.conflict(actor, roles, roles, "payroll.run.approve", Map.of()))
                .hasValueSatisfying(conflict -> {
                    assertThat(conflict.code()).isEqualTo(SodRuleCatalog.SOD_ROLE_CONFLICT);
                    assertThat(conflict.detail()).isEqualTo("payroll-preparer+payroll-approver");
                });
        assertThat(SodRuleCatalog.conflict(actor, roles, roles, "directory.person.read",
                Map.of())).isEmpty();
    }

    @Test
    void auditorCannotHoldManagementRole() {
        Set<String> roles = Set.of(SystemRoleCatalog.AUDITOR,
                SystemRoleCatalog.PAYROLL_PREPARER);
        assertThat(SodRuleCatalog.conflict(actor, roles, roles, "directory.person.read", Map.of()))
                .hasValueSatisfying(conflict ->
                        assertThat(conflict.detail()).isEqualTo("auditor+payroll-preparer"));
        assertThat(SodRuleCatalog.roleConflict(roles)).isPresent();
    }

    @Test
    void attributeRuleBlocksSelfAndMissingValues() {
        Set<String> roles = Set.of(SystemRoleCatalog.PAYROLL_REVIEWER);
        assertThat(SodRuleCatalog.conflict(actor, roles, roles, "payroll.run.review",
                Map.of("preparedBy", actor)))
                .hasValueSatisfying(conflict ->
                        assertThat(conflict.code()).isEqualTo(SodRuleCatalog.SOD_CONFLICT));
        assertThat(SodRuleCatalog.conflict(actor, roles, roles, "payroll.run.review", Map.of()))
                .hasValueSatisfying(conflict ->
                        assertThat(conflict.code())
                                .isEqualTo(SodRuleCatalog.SOD_ATTRIBUTE_MISSING));
        assertThat(SodRuleCatalog.conflict(actor, roles, roles, "payroll.run.review",
                Map.of("preparedBy", UUID.randomUUID().toString()))).isEmpty();
    }

    @Test
    void assignmentRuleOnlyAppliesToHighRiskTargets() {
        Set<String> roles = Set.of(SystemRoleCatalog.SECURITY_ADMIN);
        assertThat(SodRuleCatalog.conflict(actor, roles, roles,
                "authorization.assignment.manage",
                Map.of("targetActorId", actor, "highRisk", true)))
                .isPresent();
        assertThat(SodRuleCatalog.conflict(actor, roles, roles,
                "authorization.assignment.manage",
                Map.of("targetActorId", actor, "highRisk", false))).isEmpty();
    }

    @Test
    void highPrivilegeRolesAreDerivedFromPermissionSets() {
        assertThat(SystemRoleCatalog.highRisk(SystemRoleCatalog.TENANT_OWNER)).isTrue();
        assertThat(SystemRoleCatalog.highRisk(SystemRoleCatalog.SECURITY_ADMIN)).isTrue();
        assertThat(SystemRoleCatalog.highRisk(SystemRoleCatalog.PAYROLL_APPROVER)).isTrue();
        assertThat(SystemRoleCatalog.highRisk(SystemRoleCatalog.AUDITOR)).isFalse();
        assertThat(SystemRoleCatalog.highRisk(SystemRoleCatalog.EMPLOYEE)).isFalse();
        assertThat(SodRuleCatalog.highPrivilegePermissions())
                .contains("authorization.assignment.manage", "payroll.export");
    }
}
