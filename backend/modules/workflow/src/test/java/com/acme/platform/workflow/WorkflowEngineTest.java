package com.acme.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.RequestContextHolder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 引擎单元测试：用假 Store 验证「不会自动通过」「无审批人写异常」与正常收敛。
 *
 * <p>并行/汇合与版本钉住由 {@code WorkflowDbTest} 在真实 PostgreSQL 上覆盖。
 */
class WorkflowEngineTest {

    private WorkflowStore store;
    private AssigneeResolver assigneeResolver;
    private OutboxEventPublisher publisher;
    private WorkflowEngine engine;

    private final UUID instanceId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();
    private final UUID definitionId = UUID.randomUUID();
    private final UUID initiator = UUID.randomUUID();
    private final UUID assignee = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RequestContextHolder.clear();
        store = mock(WorkflowStore.class);
        assigneeResolver = mock(AssigneeResolver.class);
        publisher = mock(OutboxEventPublisher.class);
        engine = new WorkflowEngine(store, new WorkflowConditionEvaluator(), assigneeResolver,
                publisher, new ObjectMapper());
    }

    private WorkflowStore.InstanceRow instance(String status) {
        return new WorkflowStore.InstanceRow(instanceId, definitionId, 1, "demo", "generic",
                null, null, status, "{}", initiator, OffsetDateTime.now(), null);
    }

    private WorkflowStore.TokenRow token(String nodeKey) {
        return new WorkflowStore.TokenRow(tokenId, instanceId, nodeKey, "active");
    }

    private void definition(String schema) {
        when(store.definition(definitionId)).thenReturn(Optional.of(
                new WorkflowStore.DefinitionRow(definitionId, "demo", 1, "演示", "published",
                        schema, "hash", "tester", OffsetDateTime.now())));
    }

    @Test
    void stopsAtApprovalWithoutAutoApproving() {
        definition("""
                {"nodes":[
                  {"key":"start","type":"start"},
                  {"key":"approve","type":"approval","assignee":{"kind":"unit_head"}},
                  {"key":"end","type":"end"}],
                 "edges":[
                  {"from":"start","to":"approve"},
                  {"from":"approve","to":"end","when":"approved"}]}
                """);
        when(store.lockInstance(instanceId)).thenReturn(instance("running"));
        when(store.token(tokenId)).thenReturn(Optional.of(token("start")), Optional.of(token("approve")));
        when(assigneeResolver.resolve(any(), eq(initiator), any())).thenReturn(List.of(assignee));
        when(store.insertTask(any())).thenReturn(UUID.randomUUID());

        engine.advance(instanceId, tokenId);

        ArgumentCaptor<WorkflowStore.NewTask> captor =
                ArgumentCaptor.forClass(WorkflowStore.NewTask.class);
        verify(store).insertTask(captor.capture());
        assertThat(captor.getValue().assigneePersonId()).isEqualTo(assignee);
        verify(store).updateTokenStatus(tokenId, "waiting");
        verify(store, org.mockito.Mockito.never()).updateInstanceStatus(eq(instanceId),
                eq("completed"), any());
    }

    @Test
    void unassignedApprovalWritesExceptionAndNeverApproves() {
        definition("""
                {"nodes":[
                  {"key":"start","type":"start"},
                  {"key":"approve","type":"approval","assignee":{"kind":"unit_head"}},
                  {"key":"end","type":"end"}],
                 "edges":[
                  {"from":"start","to":"approve"},
                  {"from":"approve","to":"end","when":"approved"}]}
                """);
        when(store.lockInstance(instanceId)).thenReturn(instance("running"));
        when(store.token(tokenId)).thenReturn(Optional.of(token("start")), Optional.of(token("approve")));
        when(assigneeResolver.resolve(any(), eq(initiator), any())).thenReturn(List.of());
        when(store.insertTask(any())).thenReturn(UUID.randomUUID());

        engine.advance(instanceId, tokenId);

        verify(store).insertException(eq(instanceId), isNull(), eq("ASSIGNEE_UNRESOLVED"),
                anyString(), anyString());
        ArgumentCaptor<WorkflowStore.NewTask> captor =
                ArgumentCaptor.forClass(WorkflowStore.NewTask.class);
        verify(store).insertTask(captor.capture());
        assertThat(captor.getValue().assigneePersonId()).isNull();
        verify(store).updateTokenStatus(tokenId, "waiting");
    }

    @Test
    void completesInstanceWhenNoOpenTokensRemain() {
        definition("""
                {"nodes":[
                  {"key":"start","type":"start"},
                  {"key":"end","type":"end"}],
                 "edges":[{"from":"start","to":"end"}]}
                """);
        when(store.lockInstance(instanceId)).thenReturn(instance("running"));
        when(store.token(tokenId)).thenReturn(Optional.of(token("start")), Optional.of(token("end")));
        when(store.countTokens(instanceId, "active")).thenReturn(0);
        when(store.countTokens(instanceId, "waiting")).thenReturn(0);

        engine.advance(instanceId, tokenId);

        verify(store).updateInstanceStatus(eq(instanceId), eq("completed"), any());
    }

    @Test
    void pausedInstanceIsNotAdvanced() {
        when(store.lockInstance(instanceId)).thenReturn(instance("returned"));

        engine.advance(instanceId, tokenId);

        verify(store, org.mockito.Mockito.never()).token(any());
    }
}
