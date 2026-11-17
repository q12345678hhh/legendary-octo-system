package com.acme.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.authorization.RoleMembershipQueries;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL 上的工作流闭环。
 *
 * <p>覆盖：发布不可变、一 key 一 published、实例钉住版本、business_key 幂等、
 * 直连任务去重、日志只追加、无审批人不自动通过、SLA 超时不自动批准、跨租户隔离。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class WorkflowDbTest {

    private JdbcTemplate jdbc;
    private TenantTransactions transactions;
    private WorkflowStore store;
    private WorkflowDefinitionService definitions;
    private WorkflowInstanceService instances;
    private WorkflowTaskService tasks;
    private WorkflowSlaService sla;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private static final String SCHEMA_V1 = """
            {"nodes":[
              {"key":"start","type":"start"},
              {"key":"approve","type":"approval","assignee":{"kind":"person",
               "personId":"00000000-0000-4000-8000-000000000001"}},
              {"key":"end","type":"end"}],
             "edges":[
              {"from":"start","to":"approve"},
              {"from":"approve","to":"end","when":"approved"}]}
            """;

    private void bootstrap() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TenantTransactions(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

        String suffix = Long.toString(System.nanoTime(), 36);
        transactions.inTenant(context(tenantA), context -> {
            jdbc.update("""
                    INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
                    VALUES (?, ?, ?, 'active', 'standard')
                    """, tenantA, "wf-a-" + suffix, "工作流甲");
            return null;
        });
        transactions.inTenant(context(tenantB), context -> {
            jdbc.update("""
                    INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
                    VALUES (?, ?, ?, 'active', 'standard')
                    """, tenantB, "wf-b-" + suffix, "工作流乙");
            return null;
        });

        store = new WorkflowStore(jdbc);
        WorkflowSchemaValidator validator = new WorkflowSchemaValidator();
        definitions = new WorkflowDefinitionService(store, transactions, validator, mapper);
        OrganizationScopeQueries organization = new OrganizationScopeQueries(jdbc);
        AssigneeResolver resolver = new AssigneeResolver(organization,
                new RoleMembershipQueries(jdbc));
        OutboxEventPublisher publisher = new OutboxEventPublisher(jdbc, mapper, "test");
        WorkflowEngine engine = new WorkflowEngine(store, new WorkflowConditionEvaluator(),
                resolver, publisher, mapper);
        instances = new WorkflowInstanceService(store, transactions, engine, mapper);
        tasks = new WorkflowTaskService(store, transactions, engine, List.of(), publisher,
                organization, mapper);
        sla = new WorkflowSlaService(store, new WorkflowTenantWork(jdbc, transactions),
                organization, publisher);
    }

    private RequestContext context(UUID tenantId) {
        return new RequestContext(tenantId, null, "wf-test", "trace-" + System.nanoTime());
    }

    @Test
    void workflowLifecycle() {
        bootstrap();
        RequestContext ctxA = context(tenantA);
        RequestContextHolder.set(ctxA);
        try {
            String key = "demo.leave";
            WorkflowDefinitionService.DefinitionView v1 =
                    definitions.createDraft(key, "请假审批", SCHEMA_V1, "tester");
            definitions.publish(v1.id());

            // 一 key 一 published：直接再插一条 published 必须被唯一索引拒绝。
            assertThatThrownBy(() -> transactions.inTenant(ctxA, context -> {
                jdbc.update("""
                        INSERT INTO workflow_definition
                            (tenant_id, key, version, name, status, schema, schema_hash, created_by)
                        VALUES (?, ?, 9, '重复发布', 'published', CAST(? AS jsonb), 'h', 'tester')
                        """, tenantA, key, SCHEMA_V1);
                return null;
            })).isInstanceOf(RuntimeException.class);

            // 发布后 schema 不可改。
            assertThatThrownBy(() -> transactions.inTenant(ctxA, context -> {
                jdbc.update("UPDATE workflow_definition SET schema = '{}'::jsonb WHERE id = ?",
                        v1.id());
                return null;
            })).isInstanceOf(RuntimeException.class);

            // 启动：审批人 person 不在职 -> 无审批人任务 + 异常，且绝不自动通过。
            WorkflowViews.InstanceView started = instances.start(key, "generic", null, "bk-1",
                    java.util.Map.of("days", 2), actor);
            assertThat(started.status()).isEqualTo("running");

            WorkflowViews.InstanceDetail detail = instances.detail(started.id());
            assertThat(detail.tasks()).hasSize(1);
            assertThat(detail.tasks().getFirst().assigneePersonId()).isNull();
            Integer unresolved = transactions.inTenant(ctxA, context -> jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_exception
                     WHERE instance_id = ? AND code = 'ASSIGNEE_UNRESOLVED'
                    """, Integer.class, started.id()));
            assertThat(unresolved).isEqualTo(1);

            // 管理员显式通过才结束，未分配任务不会被自动推进。
            tasks.complete(detail.tasks().getFirst().id(), actor, "approve", "同意", true);
            assertThat(instances.detail(started.id()).instance().status()).isEqualTo("completed");

            // business_key 幂等：同一业务键返回同一实例。
            WorkflowViews.InstanceView again = instances.start(key, "generic", null, "bk-1",
                    java.util.Map.of(), actor);
            assertThat(again.id()).isEqualTo(started.id());

            // 发布 v2 后，新实例钉住 v2，旧实例仍是 v1。
            WorkflowDefinitionService.DefinitionView draft =
                    definitions.createDraft(key, "请假审批 v2", SCHEMA_V1, "tester");
            definitions.publish(draft.id());
            WorkflowViews.InstanceView v2Instance = instances.start(key, "generic", null, "bk-2",
                    java.util.Map.of(), actor);
            assertThat(v2Instance.definitionVersion()).isEqualTo(2);
            assertThat(instances.detail(started.id()).instance().definitionVersion()).isEqualTo(1);

            // 状态流转日志只追加。
            UUID logId = transactions.inTenant(ctxA, context -> jdbc.queryForObject("""
                    SELECT id FROM workflow_transition_log WHERE instance_id = ?
                     ORDER BY occurred_at LIMIT 1
                    """, UUID.class, started.id()));
            assertThatThrownBy(() -> transactions.inTenant(ctxA, context -> {
                jdbc.update("UPDATE workflow_transition_log SET comment = '篡改' WHERE id = ?", logId);
                return null;
            })).isInstanceOf(RuntimeException.class);

            // 直连任务按业务对象去重；markDecided 只关任务，不回调业务。
            UUID businessId = UUID.randomUUID();
            WorkflowViews.TaskView direct = tasks.openDirectTask("worklog.time", businessId,
                    "review", "工时待审核", "direct", actor, null);
            WorkflowViews.TaskView duplicate = tasks.openDirectTask("worklog.time", businessId,
                    "review", "工时待审核", "direct", actor, null);
            assertThat(duplicate.id()).isEqualTo(direct.id());
            tasks.markDecided("worklog.time", businessId, actor, "approve", "通过");
            assertThat(tasks.openDirectTask("worklog.time", businessId, "review", "工时待审核",
                    "direct", actor, null).status()).isEqualTo("completed");

            // SLA：到期只标记超时并写异常，任务仍待办、实例仍运行。
            WorkflowViews.InstanceView slaInstance = instances.start(key, "generic", null, "bk-3",
                    java.util.Map.of(), actor);
            UUID slaTaskId = instances.detail(slaInstance.id()).tasks().getFirst().id();
            transactions.inTenant(ctxA, context -> {
                jdbc.update("""
                        UPDATE workflow_task SET due_at = now() - interval '1 minute' WHERE id = ?
                        """, slaTaskId);
                return null;
            });
            sla.scan();
            RequestContextHolder.set(ctxA);
            WorkflowViews.TaskView afterSla = transactions.inTenant(ctxA, context ->
                    store.task(slaTaskId).map(WorkflowViews::task).orElseThrow());
            assertThat(afterSla.status()).isEqualTo("pending");
            assertThat(afterSla.slaState()).isEqualTo("breached");
            assertThat(instances.detail(slaInstance.id()).instance().status()).isEqualTo("running");

            // 跨租户隔离：租户 B 看不到租户 A 的定义与实例。
            RequestContextHolder.set(context(tenantB));
            List<WorkflowStore.DefinitionRow> visibleDefinitions = transactions.inTenant(
                    context(tenantB), context -> store.definitions());
            assertThat(visibleDefinitions).isEmpty();
            Optional<WorkflowStore.InstanceRow> foreignInstance = transactions.inTenant(
                    context(tenantB), context -> store.instance(started.id()));
            assertThat(foreignInstance).isEmpty();
        } finally {
            RequestContextHolder.clear();
        }
    }
}
