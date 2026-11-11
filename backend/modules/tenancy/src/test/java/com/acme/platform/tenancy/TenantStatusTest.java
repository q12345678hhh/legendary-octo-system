package com.acme.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantStatusTest {

    @Test
    void onlyAllowedTransitionsArePermitted() {
        assertThat(TenantStatus.PROVISIONING.canTransitionTo(TenantStatus.ACTIVE)).isTrue();
        assertThat(TenantStatus.PROVISIONING.canTransitionTo(TenantStatus.FROZEN)).isFalse();
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.FROZEN)).isTrue();
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.CLOSING)).isTrue();
        assertThat(TenantStatus.FROZEN.canTransitionTo(TenantStatus.ACTIVE)).isTrue();
        assertThat(TenantStatus.FROZEN.canTransitionTo(TenantStatus.CLOSING)).isTrue();
        assertThat(TenantStatus.CLOSING.canTransitionTo(TenantStatus.ACTIVE)).isTrue();
        assertThat(TenantStatus.CLOSING.canTransitionTo(TenantStatus.DELETED)).isTrue();
        assertThat(TenantStatus.DELETED.canTransitionTo(TenantStatus.ACTIVE)).isFalse();
        assertThat(TenantStatus.DELETED.canTransitionTo(TenantStatus.FROZEN)).isFalse();
    }

    @Test
    void onlyActiveIsWritableAndExecutable() {
        for (TenantStatus status : TenantStatus.values()) {
            assertThat(status.writable()).isEqualTo(status == TenantStatus.ACTIVE);
            assertThat(status.executable()).isEqualTo(status == TenantStatus.ACTIVE);
        }
    }

    @Test
    void wireNamesRoundTrip() {
        for (TenantStatus status : TenantStatus.values()) {
            assertThat(TenantStatus.fromWire(status.wire())).isEqualTo(status);
        }
    }
}
