package com.acme.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 字段权限：邮箱默认只对 HR/老板开放，字段级拒绝覆盖允许。 */
class FieldPolicyEvaluatorTest {

    @Test
    void emailDefaultsAndDenyPrecedence() {
        AuthorizationStore store = mock(AuthorizationStore.class);
        when(store.fieldPolicies(anyCollection(), any())).thenReturn(List.of());
        FieldPolicyEvaluator evaluator = new FieldPolicyEvaluator(store);

        var employee = evaluator.rules(Set.of(SystemRoleCatalog.EMPLOYEE), "directory.person");
        assertThat(employee.get("displayName").view()).isTrue();
        assertThat(employee.get("primaryEmail").view()).isFalse();

        var hr = evaluator.rules(Set.of(SystemRoleCatalog.HR_ADMIN), "directory.person");
        assertThat(hr.get("primaryEmail").view()).isTrue();
        assertThat(hr.get("primaryEmail").ai()).isFalse();

        when(store.fieldPolicies(anyCollection(), any())).thenReturn(List.of(
                new AuthorizationStore.FieldPolicyRow(SystemRoleCatalog.HR_ADMIN, "primaryEmail",
                        true, false, true, true, "allow"),
                new AuthorizationStore.FieldPolicyRow(SystemRoleCatalog.HR_ADMIN, "displayName",
                        false, false, false, false, "deny")));
        var overridden = evaluator.rules(Set.of(SystemRoleCatalog.HR_ADMIN), "directory.person");
        assertThat(overridden.get("primaryEmail").export()).isTrue();
        assertThat(overridden.get("displayName").view()).isFalse();
    }
}
