package com.acme.platform.appcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 模块启用状态机：默认启用、未安装拦截、幂等、版本冲突与审计写入。 */
class ModuleActivationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    private ModuleActivationStore activationStore;
    private AppCatalogStore catalogStore;
    private ModuleActivationService service;

    @BeforeEach
    void setUp() {
        activationStore = mock(ModuleActivationStore.class);
        catalogStore = mock(AppCatalogStore.class);
        TenantTransactions transactions = mock(TenantTransactions.class);
        when(transactions.inTenant(any(Function.class))).thenAnswer(invocation -> {
            Function<RequestContext, Object> work = invocation.getArgument(0);
            return work.apply(new RequestContext(TENANT, "actor", "corr", "trace"));
        });
        service = new ModuleActivationService(activationStore, catalogStore, transactions);
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    @Test
    void baseModulesStayEnabledWithoutCatalogRecords() {
        // 存量租户的目录里可能没有基础模块记录：缺失必须视为启用，不能被前端隐藏或被过滤器拦截。
        RequestContextHolder.set(new RequestContext(TENANT, null, "corr", "trace"));
        when(activationStore.moduleCodes()).thenReturn(List.of("a001", "expense"));
        when(activationStore.disabledCodes()).thenReturn(Set.of("expense"));

        Set<String> enabled = service.enabledModuleCodes();

        assertThat(enabled).contains("platform.workbench", "organization", "worklog", "a001");
        assertThat(enabled).doesNotContain("expense");
    }

    @Test
    void missingRecordIsTreatedAsEnabled() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "installed")));
        when(activationStore.find(moduleId)).thenReturn(Optional.empty());

        ModuleActivationService.ActivationView view = service.get(moduleId);

        assertThat(view.status()).isEqualTo("enabled");
        assertThat(view.version()).isZero();
        assertThat(view.explicit()).isFalse();
    }

    @Test
    void enablingUninstalledModuleIsRejected() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "not_installed")));
        when(activationStore.find(moduleId)).thenReturn(Optional.of(
                new ModuleActivationStore.ActivationRow(moduleId, "disabled", "{}", 2L, null,
                        null, null, null, null)));

        assertThatThrownBy(() -> service.set(moduleId, "enabled", 2L, "", "actor"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("MODULE_NOT_INSTALLED");
                });
        verify(activationStore, never()).insert(any(), anyString(), anyString(), anyString());
        verify(activationStore, never()).updateStatus(any(), anyString(), anyLong(), anyString(),
                anyString());
    }

    @Test
    void repeatingTheSameStatusIsIdempotent() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "installed")));
        when(activationStore.find(moduleId)).thenReturn(Optional.of(
                new ModuleActivationStore.ActivationRow(moduleId, "enabled", "{}", 3L, null,
                        null, null, null, null)));

        ModuleActivationService.ActivationView view = service.set(moduleId, "enabled", 3L, "", "actor");

        assertThat(view.status()).isEqualTo("enabled");
        assertThat(view.version()).isEqualTo(3L);
        verify(activationStore, never()).updateStatus(any(), anyString(), anyLong(), anyString(),
                anyString());
        verify(activationStore, never()).insert(any(), anyString(), anyString(), anyString());
    }

    @Test
    void staleVersionIsRejected() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "installed")));
        when(activationStore.find(moduleId)).thenReturn(Optional.of(
                new ModuleActivationStore.ActivationRow(moduleId, "enabled", "{}", 3L, null,
                        null, null, null, null)));

        assertThatThrownBy(() -> service.set(moduleId, "disabled", 2L, "", "actor"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("MODULE_ACTIVATION_VERSION_CONFLICT");
                });
        verify(activationStore, never()).updateStatus(any(), anyString(), anyLong(), anyString(),
                anyString());
    }

    @Test
    void disablingWritesAStatusChangeWithTheCurrentVersion() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "installed")));
        when(activationStore.find(moduleId)).thenReturn(
                Optional.of(new ModuleActivationStore.ActivationRow(moduleId, "enabled", "{}", 3L,
                        null, null, null, null, null)),
                Optional.of(new ModuleActivationStore.ActivationRow(moduleId, "disabled", "{}", 4L,
                        null, null, null, null, null)));
        when(activationStore.updateStatus(moduleId, "disabled", 3L, "actor", "停用"))
                .thenReturn(true);

        ModuleActivationService.ActivationView view = service.set(moduleId, "disabled", 3L, "停用",
                "actor");

        assertThat(view.status()).isEqualTo("disabled");
        assertThat(view.version()).isEqualTo(4L);
        verify(activationStore).updateStatus(eq(moduleId), eq("disabled"), eq(3L), eq("actor"),
                eq("停用"));
    }

    @Test
    void dispositionWithoutRecordInsertsTheExplicitRow() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "installed")));
        when(activationStore.find(moduleId)).thenReturn(
                Optional.empty(),
                Optional.of(new ModuleActivationStore.ActivationRow(moduleId, "disabled", "{}", 1L,
                        null, null, null, null, null)));

        ModuleActivationService.ActivationView view = service.set(moduleId, "disabled", 0L, "停用",
                "actor");

        assertThat(view.status()).isEqualTo("disabled");
        verify(activationStore).insert(moduleId, "disabled", "actor", "停用");
    }

    @Test
    void enabledModulesIncludeImplementedModulesMissingFromCatalog() {
        when(activationStore.moduleCodes()).thenReturn(java.util.List.of("a001"));
        when(activationStore.disabledCodes()).thenReturn(Set.of("expense"));
        RequestContextHolder.set(new RequestContext(TENANT, "actor", "corr", "trace"));
        try {
            Set<String> enabled = service.enabledModuleCodes();

            // 存量租户目录里没有基础模块记录，也不能因此把基础模块判为停用。
            assertThat(enabled).contains("platform.workbench", "worklog", "a001");
            assertThat(enabled).doesNotContain("expense");
        } finally {
            RequestContextHolder.clear();
        }
    }

    @Test
    void enablingWithDisabledDependencyIsRejected() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(
                module(moduleId, "installed", "[\"insights\"]")));
        when(activationStore.find(moduleId)).thenReturn(Optional.of(
                new ModuleActivationStore.ActivationRow(moduleId, "disabled", "{}", 1L, null,
                        null, null, null, null)));
        when(activationStore.moduleCodes()).thenReturn(List.of("hr.attendance"));
        when(activationStore.disabledCodes()).thenReturn(Set.of("insights", "hr.attendance"));

        assertThatThrownBy(() -> service.set(moduleId, "enabled", 1L, "", "actor"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("MODULE_DEPENDENCY_DISABLED");
                    assertThat(error.getMessage()).contains("insights");
                });
        verify(activationStore, never()).updateStatus(any(), anyString(), anyLong(), anyString(),
                anyString());
    }

    @Test
    void disablingModuleWithEnabledDependentIsRejected() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "installed")));
        when(activationStore.find(moduleId)).thenReturn(Optional.of(
                new ModuleActivationStore.ActivationRow(moduleId, "enabled", "{}", 2L, null,
                        null, null, null, null)));
        when(activationStore.enabledDependents("worklog")).thenReturn(List.of("expense"));

        assertThatThrownBy(() -> service.set(moduleId, "disabled", 2L, "", "actor"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("MODULE_DEPENDENT_ENABLED");
                    assertThat(error.getMessage()).contains("expense");
                });
        verify(activationStore, never()).updateStatus(any(), anyString(), anyLong(), anyString(),
                anyString());
    }

    @Test
    void activationEventsAreListedNewestFirstAsStored() {
        UUID moduleId = UUID.randomUUID();
        when(catalogStore.module(moduleId)).thenReturn(Optional.of(module(moduleId, "installed")));
        when(activationStore.events(moduleId)).thenReturn(List.of(
                new ModuleActivationStore.ActivationEventRow(UUID.randomUUID(), "disable",
                        "enabled", "disabled", "actor", "停用", OffsetDateTime.now()),
                new ModuleActivationStore.ActivationEventRow(UUID.randomUUID(), "enable",
                        "disabled", "enabled", "actor", "启用", OffsetDateTime.now())));

        List<ModuleActivationService.ActivationEventView> events = service.events(moduleId);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).action()).isEqualTo("disable");
        assertThat(events.get(1).comment()).isEqualTo("启用");
    }

    private static AppCatalogStore.ModuleRow module(UUID id, String installStatus) {
        return module(id, installStatus, "[]");
    }

    private static AppCatalogStore.ModuleRow module(UUID id, String installStatus,
                                                    String dependencies) {
        return new AppCatalogStore.ModuleRow(id, "worklog", "工时", "基础", "", null, null,
                "1.0.0", dependencies, "tenant", "healthy", installStatus, null, null,
                "enabled", 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
