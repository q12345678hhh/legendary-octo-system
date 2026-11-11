package com.acme.platform.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 部门树禁止自指与成环，已有数据被人工改坏时也不能把请求拖死。 */
class OrganizationTreeTest {

    private static final UUID ROOT = uuid(1);
    private static final UUID DIVISION = uuid(2);
    private static final UUID DEPARTMENT = uuid(3);
    private static final UUID OTHER = uuid(4);

    @Test
    void movingToRootIsAlwaysAllowed() {
        assertThatCode(() -> OrgTreeRules.ensureNoCycle(ROOT, null, id -> Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void unitCannotBecomeItsOwnParent() {
        assertThatThrownBy(() -> OrgTreeRules.ensureNoCycle(ROOT, ROOT, id -> Optional.empty()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ORG_UNIT_CYCLE"));
    }

    @Test
    void unitCannotMoveUnderItsDescendant() {
        Map<UUID, UUID> parents = new HashMap<>();
        parents.put(DIVISION, ROOT);
        parents.put(DEPARTMENT, DIVISION);
        assertThatThrownBy(() -> OrgTreeRules.ensureNoCycle(ROOT, DEPARTMENT,
                id -> Optional.ofNullable(parents.get(id))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ORG_UNIT_CYCLE"));
    }

    @Test
    void siblingMoveIsAllowed() {
        Map<UUID, UUID> parents = new HashMap<>();
        parents.put(DIVISION, ROOT);
        parents.put(DEPARTMENT, DIVISION);
        assertThatCode(() -> OrgTreeRules.ensureNoCycle(DEPARTMENT, OTHER,
                id -> Optional.ofNullable(parents.get(id))))
                .doesNotThrowAnyException();
    }

    @Test
    void preExistingCycleIsRejected() {
        Map<UUID, UUID> parents = new HashMap<>();
        parents.put(ROOT, OTHER);
        parents.put(OTHER, ROOT);
        assertThatThrownBy(() -> OrgTreeRules.ensureNoCycle(DEPARTMENT, ROOT,
                id -> Optional.ofNullable(parents.get(id))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ORG_UNIT_CYCLE"));
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value));
    }
}
