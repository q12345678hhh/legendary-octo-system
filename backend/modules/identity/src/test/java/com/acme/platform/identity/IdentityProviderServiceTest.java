package com.acme.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityProviderServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final ActorContext actor = new ActorContext(tenantId, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "user", true, UUID.randomUUID());

    private IdentityProviderStore store;
    private IdentityProviderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantTransactions tenantTransactions = mock(TenantTransactions.class);
        when(tenantTransactions.inTenant(any(RequestContext.class), any(Function.class)))
                .thenAnswer(invocation -> ((Function<RequestContext, Object>) invocation.getArgument(1))
                        .apply(invocation.getArgument(0)));
        RequestContext context = new RequestContext(tenantId, null, "corr-test", "trace-test");
        when(tenantTransactions.inTenant(any(Function.class)))
                .thenAnswer(invocation -> ((Function<RequestContext, Object>) invocation.getArgument(0))
                        .apply(context));
        store = mock(IdentityProviderStore.class);
        service = new IdentityProviderService(tenantTransactions, store);
    }

    @Test
    void rejectsActiveProviderBecauseConnectorIsNotAvailable() {
        assertThatThrownBy(() -> service.create(new IdentityProviderService.CreateCommand(
                "oidc", "企业单点登录", Map.of(), "active"), actor))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("CONNECTOR_NOT_AVAILABLE");
                });
    }

    @Test
    void rejectsUnknownProviderType() {
        assertThatThrownBy(() -> service.create(new IdentityProviderService.CreateCommand(
                "ldap", "目录", Map.of(), "draft"), actor))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_TYPE_INVALID"));
    }

    @Test
    void createsDraftProvider() {
        IdentityProvider created = new IdentityProvider(UUID.randomUUID(), tenantId, "oidc", "企业单点登录",
                "draft", Map.of("issuer", "https://idp.example.test"),
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now());
        when(store.find(any())).thenReturn(Optional.of(created));

        IdentityProvider provider = service.create(new IdentityProviderService.CreateCommand(
                "oidc", "企业单点登录", Map.of("issuer", "https://idp.example.test"), null), actor);

        assertThat(provider.status()).isEqualTo("draft");
        assertThat(provider.providerType()).isEqualTo("oidc");
    }
}
