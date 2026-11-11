package com.acme.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantExecutionGateTest {

    @Mock
    private TenantStatusQueries queries;

    @Test
    void activeTenantAllowsBusinessPluginAndAi() {
        RequestContext context = context();
        when(queries.find(context)).thenReturn(Optional.of(tenant(TenantStatus.ACTIVE)));
        TenantExecutionGate gate = new TenantExecutionGate(queries);

        assertThat(gate.assertBusinessWrite(context).status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(gate.assertPluginExecution(context).status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(gate.assertAiExecution(context).status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void frozenTenantBlocksAllThreeExecutions() {
        RequestContext context = context();
        when(queries.find(context)).thenReturn(Optional.of(tenant(TenantStatus.FROZEN)));
        TenantExecutionGate gate = new TenantExecutionGate(queries);

        assertCode(() -> gate.assertBusinessWrite(context), 423, "TENANT_FROZEN");
        assertCode(() -> gate.assertPluginExecution(context), 423, "TENANT_PLUGIN_BLOCKED");
        assertCode(() -> gate.assertAiExecution(context), 423, "TENANT_AI_BLOCKED");
    }

    @Test
    void closingTenantIsBlockedAndDeletedTenantIsGone() {
        RequestContext context = context();
        when(queries.find(context)).thenReturn(Optional.of(tenant(TenantStatus.CLOSING)));
        TenantExecutionGate gate = new TenantExecutionGate(queries);
        assertCode(() -> gate.assertBusinessWrite(context), 423, "TENANT_FROZEN");

        when(queries.find(context)).thenReturn(Optional.of(tenant(TenantStatus.DELETED)));
        assertCode(() -> gate.assertBusinessWrite(context), 410, "TENANT_DELETED");
    }

    @Test
    void missingTenantOrContextIsRejected() {
        RequestContext context = context();
        when(queries.find(context)).thenReturn(Optional.empty());
        TenantExecutionGate gate = new TenantExecutionGate(queries);

        assertCode(() -> gate.assertBusinessWrite(context), 404, "TENANT_NOT_FOUND");
        assertCode(() -> gate.assertBusinessWrite(null), 400, "TENANT_REQUIRED");
    }

    private void assertCode(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(status);
                    assertThat(error.code()).isEqualTo(code);
                });
    }

    private TenantRecord tenant(TenantStatus status) {
        return new TenantRecord(UUID.randomUUID(), "demo", "演示", status, "standard",
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null);
    }

    private RequestContext context() {
        return new RequestContext(UUID.randomUUID(), "ops-a",
                UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""));
    }
}
