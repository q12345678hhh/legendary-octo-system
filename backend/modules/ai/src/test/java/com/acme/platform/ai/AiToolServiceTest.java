package com.acme.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AI 工具目录：编码/地址校验、幂等启停、版本冲突与评分范围。 */
class AiToolServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TOOL = UUID.randomUUID();

    private AiToolStore store;
    private AiToolService service;

    @BeforeEach
    void setUp() {
        store = mock(AiToolStore.class);
        TenantTransactions transactions = mock(TenantTransactions.class);
        when(transactions.inTenant(any(Function.class))).thenAnswer(invocation -> {
            Function<RequestContext, Object> work = invocation.getArgument(0);
            return work.apply(new RequestContext(TENANT, "actor", "corr", "trace"));
        });
        service = new AiToolService(store, transactions);
    }

    @Test
    void registerRejectsInvalidCode() {
        assertThatThrownBy(() -> service.register(
                new AiToolService.RegisterInput("Bad Code", "名称", "", "https://example.test",
                        "{}"), "actor"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("AI_TOOL_CODE_INVALID");
                    assertThat(error.status()).isEqualTo(400);
                });
        verify(store, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void registerRejectsNonHttpEndpoint() {
        assertThatThrownBy(() -> service.register(
                new AiToolService.RegisterInput("doc.search", "文档检索", "", "ftp://example.test",
                        "{}"), "actor"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("AI_TOOL_ENDPOINT_INVALID"));
    }

    @Test
    void registerTrimsFieldsAndReturnsStoredRow() {
        when(store.findByCode("doc.search")).thenReturn(Optional.of(
                tool("doc.search", "enabled", 1L, null)));

        AiToolService.ToolView view = service.register(
                new AiToolService.RegisterInput("  doc.search ", " 文档检索 ", " 备注 ",
                        " https://example.test/tool ", ""), "actor");

        verify(store).insert("doc.search", "文档检索", "备注", "https://example.test/tool", "{}",
                "actor");
        assertThat(view.code()).isEqualTo("doc.search");
    }

    @Test
    void enablingAlreadyEnabledToolIsIdempotent() {
        when(store.find(TOOL)).thenReturn(Optional.of(tool("doc.search", "enabled", 3L, 4)));

        AiToolService.ToolView view = service.setStatus(TOOL, "enabled", 3L);

        assertThat(view.status()).isEqualTo("enabled");
        assertThat(view.rating()).isEqualTo(4);
        verify(store, never()).updateStatus(any(), anyString(), anyLong());
    }

    @Test
    void statusUpdateWithStaleVersionIsRejected() {
        when(store.find(TOOL)).thenReturn(Optional.of(tool("doc.search", "enabled", 3L, null)));

        assertThatThrownBy(() -> service.setStatus(TOOL, "disabled", 2L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("AI_TOOL_VERSION_CONFLICT");
                });
    }

    @Test
    void ratingMustBeBetweenOneAndFive() {
        assertThatThrownBy(() -> service.rate(TOOL, 6, 1L, "actor"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("AI_TOOL_RATING_INVALID"));
    }

    @Test
    void enablingUnratedToolIsRejected() {
        when(store.find(TOOL)).thenReturn(Optional.of(tool("doc.search", "disabled", 1L, null)));

        assertThatThrownBy(() -> service.setStatus(TOOL, "enabled", 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(422);
                    assertThat(error.code()).isEqualTo("AI_TOOL_RATING_REQUIRED");
                });
    }

    private static AiToolStore.ToolRow tool(String code, String status, long version,
                                            Integer rating) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AiToolStore.ToolRow(TOOL, code, "文档检索", "", "https://example.test/tool",
                "{}", status, rating, "actor-1", rating == null ? null : "actor-2", version,
                now, now);
    }
}
