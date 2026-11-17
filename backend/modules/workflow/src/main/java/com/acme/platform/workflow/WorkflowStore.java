package com.acme.platform.workflow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作流全部 JDBC 读写。
 *
 * <p>所有方法都假设调用方已经处于 {@code TenantTransactions.inTenant(...)} 事务里，
 * {@code tenant_id} 由 RLS 上下文提供，不接受客户端传入。
 */
@Component
public class WorkflowStore {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record DefinitionRow(UUID id, String key, int version, String name, String status,
                                String schema, String schemaHash, String createdBy,
                                OffsetDateTime publishedAt) {
    }

    public record InstanceRow(UUID id, UUID definitionId, int definitionVersion,
                              String definitionKey, String businessType, UUID businessId,
                              String businessKey, String status, String variables,
                              UUID startedByPersonId, OffsetDateTime startedAt,
                              OffsetDateTime endedAt) {
    }

    public record TokenRow(UUID id, UUID instanceId, String nodeKey, String status) {
    }

    public record TaskRow(UUID id, UUID instanceId, UUID tokenId, String nodeKey,
                          String businessType, UUID businessId, String taskType, String title,
                          UUID assigneePersonId, String assigneeKind, String assigneeRole,
                          String candidates, String status, String action, String comment,
                          OffsetDateTime dueAt, String slaState, UUID claimedBy,
                          OffsetDateTime createdAt) {
    }

    public record LogRow(UUID id, UUID taskId, String nodeKey, String fromStatus, String toStatus,
                         String action, UUID actorPersonId, String comment,
                         OffsetDateTime occurredAt) {
    }

    public record TimerRow(UUID id, UUID instanceId, UUID tokenId, String nodeKey,
                           OffsetDateTime fireAt) {
    }

    public record ServiceTaskRow(UUID id, UUID instanceId, UUID tokenId, String nodeKey,
                                 String handlerKey, String idempotencyKey, String status,
                                 int attempts, int maxAttempts, String lastError,
                                 UUID compensationOf, String payload,
                                 OffsetDateTime nextAttemptAt) {
    }

    public record ExceptionRow(UUID id, UUID instanceId, UUID taskId, String code, String message,
                               String payload, String status, UUID resolvedBy, String resolution,
                               OffsetDateTime createdAt) {
    }

    public record NewTask(UUID instanceId, UUID tokenId, String nodeKey, String businessType,
                          UUID businessId, String taskType, String title,
                          UUID assigneePersonId, String assigneeKind, String assigneeRole,
                          String candidates, OffsetDateTime dueAt) {
    }

    // ------------------------------------------------------------------ 定义

    private static final String DEFINITION_COLUMNS = """
            id, key, version, name, status, schema, schema_hash, created_by, published_at
            """;

    private static DefinitionRow definition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DefinitionRow(rs.getObject("id", UUID.class), rs.getString("key"),
                rs.getInt("version"), rs.getString("name"), rs.getString("status"),
                rs.getString("schema"), rs.getString("schema_hash"), rs.getString("created_by"),
                rs.getObject("published_at", OffsetDateTime.class));
    }

    public List<DefinitionRow> definitions() {
        return jdbcTemplate.query("SELECT " + DEFINITION_COLUMNS + """
                  FROM workflow_definition
                 ORDER BY key, version DESC
                """, (rs, rowNum) -> definition(rs));
    }

    public Optional<DefinitionRow> definition(UUID id) {
        return jdbcTemplate.query("SELECT " + DEFINITION_COLUMNS + """
                  FROM workflow_definition WHERE id = ?
                """, (rs, rowNum) -> definition(rs), id).stream().findFirst();
    }

    public Optional<DefinitionRow> publishedDefinition(String key) {
        return jdbcTemplate.query("SELECT " + DEFINITION_COLUMNS + """
                  FROM workflow_definition
                 WHERE key = ? AND status = 'published'
                """, (rs, rowNum) -> definition(rs), key).stream().findFirst();
    }

    public int maxVersion(String key) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(version), 0) FROM workflow_definition WHERE key = ?",
                Integer.class, key);
        return value == null ? 0 : value;
    }

    public UUID insertDefinition(String key, int version, String name, String schema,
                                 String schemaHash, String createdBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO workflow_definition
                    (tenant_id, key, version, name, schema, schema_hash, created_by)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, CAST(? AS jsonb), ?, ?)
                RETURNING id
                """, UUID.class, key, version, name, schema, schemaHash, createdBy);
    }

    public void updateDefinition(UUID id, String name, String schema, String schemaHash) {
        jdbcTemplate.update("""
                UPDATE workflow_definition
                   SET name = ?, schema = CAST(? AS jsonb), schema_hash = ?, updated_at = now()
                 WHERE id = ? AND status = 'draft'
                """, name, schema, schemaHash, id);
    }

    public void retireOtherPublished(String key, UUID keepId) {
        jdbcTemplate.update("""
                UPDATE workflow_definition
                   SET status = 'retired', retired_at = now(), updated_at = now()
                 WHERE key = ? AND status = 'published' AND id <> ?
                """, key, keepId);
    }

    public void markPublished(UUID id) {
        jdbcTemplate.update("""
                UPDATE workflow_definition
                   SET status = 'published', published_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'draft'
                """, id);
    }

    public void markRetired(UUID id) {
        jdbcTemplate.update("""
                UPDATE workflow_definition
                   SET status = 'retired', retired_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'published'
                """, id);
    }

    // ------------------------------------------------------------------ 实例

    private static final String INSTANCE_COLUMNS = """
            id, definition_id, definition_version, definition_key, business_type, business_id,
            business_key, status, variables, started_by_person_id, started_at, ended_at
            """;

    private static InstanceRow instance(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InstanceRow(rs.getObject("id", UUID.class),
                rs.getObject("definition_id", UUID.class), rs.getInt("definition_version"),
                rs.getString("definition_key"), rs.getString("business_type"),
                rs.getObject("business_id", UUID.class), rs.getString("business_key"),
                rs.getString("status"), rs.getString("variables"),
                rs.getObject("started_by_person_id", UUID.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class));
    }

    public UUID insertInstance(UUID definitionId, int definitionVersion, String definitionKey,
                               String businessType, UUID businessId, String businessKey,
                               String variables, UUID startedByPersonId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO workflow_instance
                    (tenant_id, definition_id, definition_version, definition_key,
                     business_type, business_id, business_key, variables, started_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                RETURNING id
                """, UUID.class, definitionId, definitionVersion, definitionKey, businessType,
                businessId, businessKey, variables, startedByPersonId);
    }

    public Optional<InstanceRow> instance(UUID id) {
        return jdbcTemplate.query("SELECT " + INSTANCE_COLUMNS + """
                  FROM workflow_instance WHERE id = ?
                """, (rs, rowNum) -> instance(rs), id).stream().findFirst();
    }

    public InstanceRow lockInstance(UUID id) {
        return jdbcTemplate.query("SELECT " + INSTANCE_COLUMNS + """
                  FROM workflow_instance WHERE id = ? FOR UPDATE
                """, (rs, rowNum) -> instance(rs), id).stream().findFirst()
                .orElseThrow(() -> com.acme.platform.platform.ApiException.notFound(
                        "WORKFLOW_INSTANCE_NOT_FOUND", "流程实例不存在：" + id));
    }

    public Optional<InstanceRow> instanceByBusinessKey(String businessType, String businessKey) {
        return jdbcTemplate.query("SELECT " + INSTANCE_COLUMNS + """
                  FROM workflow_instance
                 WHERE business_type = ? AND business_key = ?
                """, (rs, rowNum) -> instance(rs), businessType, businessKey)
                .stream().findFirst();
    }

    public List<InstanceRow> instancesStartedBy(UUID personId) {
        return jdbcTemplate.query("SELECT " + INSTANCE_COLUMNS + """
                  FROM workflow_instance
                 WHERE started_by_person_id = ?
                 ORDER BY started_at DESC
                 LIMIT 100
                """, (rs, rowNum) -> instance(rs), personId);
    }

    public void updateInstanceStatus(UUID id, String status, OffsetDateTime endedAt) {
        jdbcTemplate.update("""
                UPDATE workflow_instance SET status = ?, ended_at = ? WHERE id = ?
                """, status, endedAt, id);
    }

    // ------------------------------------------------------------------ 令牌

    private static TokenRow token(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TokenRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getString("node_key"),
                rs.getString("status"));
    }

    public UUID insertToken(UUID instanceId, String nodeKey, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO workflow_token (tenant_id, instance_id, node_key, status)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                RETURNING id
                """, UUID.class, instanceId, nodeKey, status);
    }

    public Optional<TokenRow> token(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, node_key, status FROM workflow_token WHERE id = ?
                """, (rs, rowNum) -> token(rs), id).stream().findFirst();
    }

    public List<TokenRow> tokens(UUID instanceId) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, node_key, status
                  FROM workflow_token WHERE instance_id = ? ORDER BY created_at
                """, (rs, rowNum) -> token(rs), instanceId);
    }

    public List<TokenRow> tokensAtNode(UUID instanceId, String nodeKey, String status) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, node_key, status
                  FROM workflow_token
                 WHERE instance_id = ? AND node_key = ? AND status = ?
                 ORDER BY created_at
                """, (rs, rowNum) -> token(rs), instanceId, nodeKey, status);
    }

    public int countTokens(UUID instanceId, String status) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM workflow_token WHERE instance_id = ? AND status = ?
                """, Integer.class, instanceId, status);
        return value == null ? 0 : value;
    }

    public void moveToken(UUID id, String nodeKey) {
        jdbcTemplate.update("""
                UPDATE workflow_token SET node_key = ?, status = 'active' WHERE id = ?
                """, nodeKey, id);
    }

    public void updateTokenStatus(UUID id, String status) {
        jdbcTemplate.update("""
                UPDATE workflow_token
                   SET status = ?, completed_at = CASE WHEN ? = 'completed' THEN now()
                                                       ELSE completed_at END
                 WHERE id = ?
                """, status, status, id);
    }

    public void cancelOpenTokens(UUID instanceId) {
        jdbcTemplate.update("""
                UPDATE workflow_token SET status = 'cancelled'
                 WHERE instance_id = ? AND status IN ('active', 'waiting')
                """, instanceId);
    }

    // ------------------------------------------------------------------ 任务

    private static final String TASK_COLUMNS = """
            id, instance_id, token_id, node_key, business_type, business_id, task_type, title,
            assignee_person_id, assignee_kind, assignee_role, candidates, status, action, comment,
            due_at, sla_state, claimed_by, created_at
            """;

    private static TaskRow task(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("token_id", UUID.class),
                rs.getString("node_key"), rs.getString("business_type"),
                rs.getObject("business_id", UUID.class), rs.getString("task_type"),
                rs.getString("title"), rs.getObject("assignee_person_id", UUID.class),
                rs.getString("assignee_kind"), rs.getString("assignee_role"),
                rs.getString("candidates"), rs.getString("status"), rs.getString("action"),
                rs.getString("comment"), rs.getObject("due_at", OffsetDateTime.class),
                rs.getString("sla_state"), rs.getObject("claimed_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public UUID insertTask(NewTask task) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO workflow_task
                    (tenant_id, instance_id, token_id, node_key, business_type, business_id,
                     task_type, title, assignee_person_id, assignee_kind, assignee_role,
                     candidates, due_at)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                RETURNING id
                """, UUID.class, task.instanceId(), task.tokenId(), task.nodeKey(),
                task.businessType(), task.businessId(), task.taskType(), task.title(),
                task.assigneePersonId(), task.assigneeKind(), task.assigneeRole(),
                task.candidates() == null ? "[]" : task.candidates(), task.dueAt());
    }

    public Optional<TaskRow> task(UUID id) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task WHERE id = ?
                """, (rs, rowNum) -> task(rs), id).stream().findFirst();
    }

    public List<TaskRow> tasksForInstance(UUID instanceId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task WHERE instance_id = ? ORDER BY created_at
                """, (rs, rowNum) -> task(rs), instanceId);
    }

    public List<TaskRow> openTasksForPerson(UUID personId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task
                 WHERE assignee_person_id = ? AND status IN ('pending', 'claimed')
                 ORDER BY created_at
                """, (rs, rowNum) -> task(rs), personId);
    }

    public List<TaskRow> openTasksForPeople(Collection<UUID> personIds) {
        if (personIds == null || personIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", Collections.nCopies(personIds.size(), "?"));
        List<Object> args = new ArrayList<>(personIds);
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task
                 WHERE assignee_person_id IN (%s) AND status IN ('pending', 'claimed')
                 ORDER BY created_at
                """.formatted(in), (rs, rowNum) -> task(rs), args.toArray());
    }

    public List<TaskRow> allOpenTasks() {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task
                 WHERE status IN ('pending', 'claimed')
                 ORDER BY created_at
                """, (rs, rowNum) -> task(rs));
    }

    public List<TaskRow> openTasksAtNode(UUID instanceId, String nodeKey) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task
                 WHERE instance_id = ? AND node_key = ? AND status IN ('pending', 'claimed')
                 ORDER BY created_at
                """, (rs, rowNum) -> task(rs), instanceId, nodeKey);
    }

    public int countPendingTasksAtNode(UUID instanceId, String nodeKey) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM workflow_task
                 WHERE instance_id = ? AND node_key = ? AND status IN ('pending', 'claimed')
                """, Integer.class, instanceId, nodeKey);
        return value == null ? 0 : value;
    }

    public Optional<TaskRow> directTask(String businessType, UUID businessId, String taskType) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task
                 WHERE instance_id IS NULL AND business_type = ? AND business_id = ?
                   AND task_type = ? AND status IN ('pending', 'claimed', 'completed', 'returned')
                 ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> task(rs), businessType, businessId, taskType)
                .stream().findFirst();
    }

    public int claimTask(UUID id, UUID personId) {
        return jdbcTemplate.update("""
                UPDATE workflow_task
                   SET status = 'claimed', claimed_at = now(), claimed_by = ?, updated_at = now()
                 WHERE id = ? AND status = 'pending'
                """, personId, id);
    }

    public int completeTask(UUID id, String action, String comment, UUID personId) {
        return jdbcTemplate.update("""
                UPDATE workflow_task
                   SET status = 'completed', action = ?, comment = ?, completed_at = now(),
                       completed_by = ?, updated_at = now()
                 WHERE id = ? AND status IN ('pending', 'claimed')
                """, action, comment, personId, id);
    }

    public int markTaskReturned(UUID id, String comment, UUID personId) {
        return jdbcTemplate.update("""
                UPDATE workflow_task
                   SET status = 'returned', action = 'return', comment = ?, completed_at = now(),
                       completed_by = ?, updated_at = now()
                 WHERE id = ? AND status IN ('pending', 'claimed')
                """, comment, personId, id);
    }

    public int cancelTask(UUID id, String comment) {
        return jdbcTemplate.update("""
                UPDATE workflow_task
                   SET status = 'cancelled', comment = coalesce(?, comment),
                       completed_at = now(), updated_at = now()
                 WHERE id = ? AND status IN ('pending', 'claimed')
                """, comment, id);
    }

    public int markTaskTransferred(UUID id, String comment, UUID personId) {
        return jdbcTemplate.update("""
                UPDATE workflow_task
                   SET status = 'transferred', action = 'transfer', comment = ?,
                       completed_at = now(), completed_by = ?, updated_at = now()
                 WHERE id = ? AND status IN ('pending', 'claimed')
                """, comment, personId, id);
    }

    public void updateTaskAssignee(UUID id, UUID personId, String kind) {
        jdbcTemplate.update("""
                UPDATE workflow_task
                   SET assignee_person_id = ?, assignee_kind = ?, status = 'pending',
                       claimed_at = NULL, claimed_by = NULL, updated_at = now()
                 WHERE id = ?
                """, personId, kind, id);
    }

    public void setSlaState(UUID id, String state) {
        jdbcTemplate.update("""
                UPDATE workflow_task SET sla_state = ?, updated_at = now() WHERE id = ?
                """, state, id);
    }

    public int markWarningTasks() {
        return jdbcTemplate.update("""
                UPDATE workflow_task
                   SET sla_state = 'warning', updated_at = now()
                 WHERE status IN ('pending', 'claimed')
                   AND sla_state = 'normal'
                   AND due_at IS NOT NULL
                   AND now() >= created_at + (due_at - created_at) * 0.8
                """);
    }

    public List<TaskRow> breachedTasks(int limit) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + """
                  FROM workflow_task
                 WHERE status IN ('pending', 'claimed')
                   AND sla_state <> 'breached'
                   AND due_at IS NOT NULL AND due_at <= now()
                 ORDER BY due_at
                 LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> task(rs), limit);
    }

    public int countCompletedApprovals(UUID instanceId) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM workflow_task
                 WHERE instance_id = ? AND status = 'completed' AND action = 'approve'
                """, Integer.class, instanceId);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ 日志

    public void log(UUID instanceId, UUID taskId, String nodeKey, String fromStatus,
                    String toStatus, String action, UUID actorPersonId, String comment) {
        jdbcTemplate.update("""
                INSERT INTO workflow_transition_log
                    (tenant_id, instance_id, task_id, node_key, from_status, to_status,
                     action, actor_person_id, comment)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                """, instanceId, taskId, nodeKey, fromStatus, toStatus, action, actorPersonId,
                comment);
    }

    public List<LogRow> logs(UUID instanceId) {
        return jdbcTemplate.query("""
                SELECT id, task_id, node_key, from_status, to_status, action,
                       actor_person_id, comment, occurred_at
                  FROM workflow_transition_log
                 WHERE instance_id = ?
                 ORDER BY occurred_at, id
                """, (rs, rowNum) -> new LogRow(rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("node_key"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("action"),
                rs.getObject("actor_person_id", UUID.class), rs.getString("comment"),
                rs.getObject("occurred_at", OffsetDateTime.class)), instanceId);
    }

    // ------------------------------------------------------------------ 定时器

    public Optional<UUID> insertTimer(UUID instanceId, UUID tokenId, String nodeKey,
                                      OffsetDateTime fireAt) {
        return jdbcTemplate.query("""
                INSERT INTO workflow_timer (tenant_id, instance_id, token_id, node_key, fire_at)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, instance_id, token_id, node_key) DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                instanceId, tokenId, nodeKey, fireAt).stream().findFirst();
    }

    public List<TimerRow> dueTimers(int limit) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, token_id, node_key, fire_at
                  FROM workflow_timer
                 WHERE status = 'pending' AND fire_at <= now()
                 ORDER BY fire_at
                 LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new TimerRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("token_id", UUID.class),
                rs.getString("node_key"), rs.getObject("fire_at", OffsetDateTime.class)), limit);
    }

    public void markTimerFired(UUID id) {
        jdbcTemplate.update("""
                UPDATE workflow_timer SET status = 'fired', fired_at = now() WHERE id = ?
                """, id);
    }

    public void cancelPendingTimers(UUID instanceId) {
        jdbcTemplate.update("""
                UPDATE workflow_timer SET status = 'cancelled'
                 WHERE instance_id = ? AND status = 'pending'
                """, instanceId);
    }

    // ------------------------------------------------------------------ 服务任务

    public Optional<UUID> insertServiceTask(UUID instanceId, UUID tokenId, String nodeKey,
                                           String handlerKey, String idempotencyKey,
                                           int maxAttempts, String payload,
                                           UUID compensationOf) {
        return jdbcTemplate.query("""
                INSERT INTO workflow_service_task
                    (tenant_id, instance_id, token_id, node_key, handler_key, idempotency_key,
                     max_attempts, payload, compensation_of)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), instanceId, tokenId, nodeKey,
                handlerKey, idempotencyKey, maxAttempts,
                payload == null ? "{}" : payload, compensationOf).stream().findFirst();
    }

    public Optional<ServiceTaskRow> serviceTask(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, token_id, node_key, handler_key, idempotency_key,
                       status, attempts, max_attempts, last_error, compensation_of, payload,
                       next_attempt_at
                  FROM workflow_service_task WHERE id = ?
                """, (rs, rowNum) -> new ServiceTaskRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("token_id", UUID.class),
                rs.getString("node_key"), rs.getString("handler_key"),
                rs.getString("idempotency_key"), rs.getString("status"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getString("last_error"),
                rs.getObject("compensation_of", UUID.class), rs.getString("payload"),
                rs.getObject("next_attempt_at", OffsetDateTime.class)), id)
                .stream().findFirst();
    }

    public List<ServiceTaskRow> runnableServiceTasks(int limit) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, token_id, node_key, handler_key, idempotency_key,
                       status, attempts, max_attempts, last_error, compensation_of, payload,
                       next_attempt_at
                  FROM workflow_service_task
                 WHERE status IN ('pending', 'failed') AND next_attempt_at <= now()
                 ORDER BY next_attempt_at
                 LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new ServiceTaskRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("token_id", UUID.class),
                rs.getString("node_key"), rs.getString("handler_key"),
                rs.getString("idempotency_key"), rs.getString("status"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getString("last_error"),
                rs.getObject("compensation_of", UUID.class), rs.getString("payload"),
                rs.getObject("next_attempt_at", OffsetDateTime.class)), limit);
    }

    public void markServiceRunning(UUID id) {
        jdbcTemplate.update("""
                UPDATE workflow_service_task
                   SET status = 'running', attempts = attempts + 1, updated_at = now()
                 WHERE id = ?
                """, id);
    }

    public void markServiceSucceeded(UUID id) {
        jdbcTemplate.update("""
                UPDATE workflow_service_task
                   SET status = 'succeeded', last_error = NULL, updated_at = now()
                 WHERE id = ?
                """, id);
    }

    public void markServiceCompensated(UUID id) {
        jdbcTemplate.update("""
                UPDATE workflow_service_task SET status = 'compensated', updated_at = now()
                 WHERE id = ?
                """, id);
    }

    public void markServiceFailed(UUID id, String error, OffsetDateTime nextAttemptAt) {
        jdbcTemplate.update("""
                UPDATE workflow_service_task
                   SET status = 'failed', last_error = ?, next_attempt_at = ?, updated_at = now()
                 WHERE id = ?
                """, error, nextAttemptAt, id);
    }

    public void resetServiceTask(UUID id) {
        jdbcTemplate.update("""
                UPDATE workflow_service_task
                   SET status = 'pending', attempts = 0, last_error = NULL,
                       next_attempt_at = now(), updated_at = now()
                 WHERE id = ?
                """, id);
    }

    public List<ServiceTaskRow> succeededCompensatable(UUID instanceId) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, token_id, node_key, handler_key, idempotency_key,
                       status, attempts, max_attempts, last_error, compensation_of, payload,
                       next_attempt_at
                  FROM workflow_service_task
                 WHERE instance_id = ? AND status = 'succeeded' AND compensation_of IS NULL
                 ORDER BY updated_at DESC
                """, (rs, rowNum) -> new ServiceTaskRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("token_id", UUID.class),
                rs.getString("node_key"), rs.getString("handler_key"),
                rs.getString("idempotency_key"), rs.getString("status"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getString("last_error"),
                rs.getObject("compensation_of", UUID.class), rs.getString("payload"),
                rs.getObject("next_attempt_at", OffsetDateTime.class)), instanceId);
    }

    // ------------------------------------------------------------------ 异常

    public UUID insertException(UUID instanceId, UUID taskId, String code, String message,
                                String payload) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO workflow_exception
                    (tenant_id, instance_id, task_id, code, message, payload)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, CAST(? AS jsonb))
                RETURNING id
                """, UUID.class, instanceId, taskId, code, message,
                payload == null ? "{}" : payload);
    }

    public List<ExceptionRow> openExceptions() {
        return jdbcTemplate.query("""
                SELECT id, instance_id, task_id, code, message, payload, status,
                       resolved_by, resolution, created_at
                  FROM workflow_exception
                 WHERE status = 'open'
                 ORDER BY created_at DESC
                 LIMIT 200
                """, (rs, rowNum) -> new ExceptionRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getString("code"), rs.getString("message"), rs.getString("payload"),
                rs.getString("status"), rs.getObject("resolved_by", UUID.class),
                rs.getString("resolution"),
                rs.getObject("created_at", OffsetDateTime.class)));
    }

    public Optional<ExceptionRow> exception(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, instance_id, task_id, code, message, payload, status,
                       resolved_by, resolution, created_at
                  FROM workflow_exception WHERE id = ?
                """, (rs, rowNum) -> new ExceptionRow(rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getString("code"), rs.getString("message"), rs.getString("payload"),
                rs.getString("status"), rs.getObject("resolved_by", UUID.class),
                rs.getString("resolution"),
                rs.getObject("created_at", OffsetDateTime.class)), id).stream().findFirst();
    }

    public void resolveException(UUID id, UUID resolvedBy, String resolution) {
        jdbcTemplate.update("""
                UPDATE workflow_exception
                   SET status = 'resolved', resolved_by = ?, resolved_at = now(), resolution = ?
                 WHERE id = ? AND status = 'open'
                """, resolvedBy, resolution, id);
    }
}
