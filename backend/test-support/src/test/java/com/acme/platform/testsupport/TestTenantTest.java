package com.acme.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TestTenantTest {

    @Test
    void sameNameProducesStableId() {
        assertThat(TestTenant.of("Acme").id()).isEqualTo(TestTenant.of("acme").id());
    }

    @Test
    void differentNamesProduceDifferentIds() {
        assertThat(TestTenant.of("acme").id()).isNotEqualTo(TestTenant.of("globex").id());
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> TestTenant.of("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
