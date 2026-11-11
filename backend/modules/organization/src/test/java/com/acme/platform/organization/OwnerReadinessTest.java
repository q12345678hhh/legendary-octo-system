package com.acme.platform.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 老板角色是启用业务模块的硬门禁：必须有人，且受托人不能只是老板自己。 */
class OwnerReadinessTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TRUSTEE = UUID.randomUUID();

    @Test
    void missingOwnerAndTrusteeBlocksEverything() {
        OrganizationReadiness readiness = OrganizationReadiness.evaluate(Set.of(), Set.of());
        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers()).containsExactly(
                OrganizationReadiness.Blocker.OWNER_ROLE_MISSING,
                OrganizationReadiness.Blocker.TRUSTEE_MISSING);
    }

    @Test
    void trusteeMustBeADistinctPerson() {
        OrganizationReadiness readiness = OrganizationReadiness.evaluate(Set.of(OWNER), Set.of(OWNER));
        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers())
                .containsExactly(OrganizationReadiness.Blocker.TRUSTEE_NOT_DISTINCT);
    }

    @Test
    void ownerAndDistinctTrusteeIsReady() {
        OrganizationReadiness readiness = OrganizationReadiness.evaluate(Set.of(OWNER), Set.of(TRUSTEE));
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.blockers()).isEmpty();
        assertThatCode(readiness::requireReady).doesNotThrowAnyException();
    }

    @Test
    void requireReadyProducesStableErrorCode() {
        OrganizationReadiness readiness = OrganizationReadiness.evaluate(Set.of(), Set.of(TRUSTEE));
        assertThatThrownBy(readiness::requireReady)
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("ORGANIZATION_NOT_READY");
                });
    }
}
