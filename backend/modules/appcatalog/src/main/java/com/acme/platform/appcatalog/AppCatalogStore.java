package com.acme.platform.appcatalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 模块目录与建议的全部 JDBC 读写。
 *
 * <p>所有方法都假设调用方已经处于 {@code TenantTransactions.inTenant(...)} 事务里，
 * {@code tenant_id} 由 RLS 上下文提供，不接受客户端传入。
 */
@Component
public class AppCatalogStore {

    private final JdbcTemplate jdbcTemplate;

    public AppCatalogStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ModuleRow(UUID id, String code, String name, String category, String description,
                            String templateKey, UUID ownerPersonId, String version,
                            String dependencies, String applicableScope, String healthStatus,
                            String installStatus, OffsetDateTime createdAt,
                            OffsetDateTime updatedAt) {
    }

    public record SuggestionRow(UUID id, String title, UUID requesterPersonId,
                                String businessProblem, String goal, String scope, String dataScope,
                                String permissionsNeeded, String costEstimate,
                                String benefitEstimate, UUID suggestedOwnerPersonId,
                                UUID configOwnerPersonId, String status,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record EventRow(UUID id, String eventKind, String fromStatus, String toStatus,
                           String action, UUID actorPersonId, UUID recipientPersonId,
                           String comment, OffsetDateTime occurredAt) {
    }

    // ------------------------------------------------------------------ 模块目录

    private static final String MODULE_COLUMNS = """
            id, code, name, category, description, template_key, owner_person_id, version,
            dependencies::text AS dependencies, applicable_scope, health_status, install_status,
            created_at, updated_at
            """;

    private static ModuleRow module(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ModuleRow(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), rs.getString("description"),
                rs.getString("template_key"), rs.getObject("owner_person_id", UUID.class),
                rs.getString("version"), rs.getString("dependencies"),
                rs.getString("applicable_scope"), rs.getString("health_status"),
                rs.getString("install_status"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public List<ModuleRow> modules(String keyword, String category, String health) {
        String like = keyword == null || keyword.isBlank()
                ? null : "%" + keyword.trim().toLowerCase() + "%";
        return jdbcTemplate.query("SELECT " + MODULE_COLUMNS + """
                  FROM app_module
                 WHERE (?::text IS NULL OR lower(code) LIKE ? OR lower(name) LIKE ?
                        OR lower(category) LIKE ?)
                   AND (?::text IS NULL OR category = ?)
                   AND (?::text IS NULL OR health_status = ?)
                 ORDER BY category, name
                """, (rs, rowNum) -> module(rs),
                like, like, like, like,
                blankToNull(category), blankToNull(category),
                blankToNull(health), blankToNull(health));
    }

    public Optional<ModuleRow> module(UUID id) {
        return jdbcTemplate.query("SELECT " + MODULE_COLUMNS + """
                  FROM app_module WHERE id = ?
                """, (rs, rowNum) -> module(rs), id).stream().findFirst();
    }

    public Optional<ModuleRow> moduleByCode(String code) {
        return jdbcTemplate.query("SELECT " + MODULE_COLUMNS + """
                  FROM app_module WHERE code = ?
                """, (rs, rowNum) -> module(rs), code).stream().findFirst();
    }

    public UUID insertModule(String code, String name, String category, String description,
                             String templateKey, UUID ownerPersonId, String version,
                             String dependencies, String applicableScope, String healthStatus,
                             String installStatus) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO app_module
                    (tenant_id, code, name, category, description, template_key, owner_person_id,
                     version, dependencies, applicable_scope, health_status, install_status)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                RETURNING id
                """, UUID.class, code, name, category, description, templateKey, ownerPersonId,
                version, dependencies, applicableScope, healthStatus, installStatus);
    }

    public void updateModule(UUID id, String name, String category, String description,
                             String templateKey, UUID ownerPersonId, String version,
                             String dependencies, String applicableScope, String healthStatus,
                             String installStatus) {
        jdbcTemplate.update("""
                UPDATE app_module
                   SET name = ?, category = ?, description = ?, template_key = ?,
                       owner_person_id = ?, version = ?, dependencies = CAST(? AS jsonb),
                       applicable_scope = ?, health_status = ?, install_status = ?,
                       updated_at = now()
                 WHERE id = ?
                """, name, category, description, templateKey, ownerPersonId, version, dependencies,
                applicableScope, healthStatus, installStatus, id);
    }

    // ------------------------------------------------------------------ 建议

    private static final String SUGGESTION_COLUMNS = """
            id, title, requester_person_id, business_problem, goal, scope, data_scope,
            permissions_needed, cost_estimate, benefit_estimate, suggested_owner_person_id,
            config_owner_person_id, status, created_at, updated_at
            """;

    private static SuggestionRow suggestion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SuggestionRow(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getObject("requester_person_id", UUID.class), rs.getString("business_problem"),
                rs.getString("goal"), rs.getString("scope"), rs.getString("data_scope"),
                rs.getString("permissions_needed"), rs.getString("cost_estimate"),
                rs.getString("benefit_estimate"),
                rs.getObject("suggested_owner_person_id", UUID.class),
                rs.getObject("config_owner_person_id", UUID.class), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public List<SuggestionRow> suggestionsForPerson(UUID requesterPersonId) {
        return jdbcTemplate.query("SELECT " + SUGGESTION_COLUMNS + """
                  FROM app_module_suggestion
                 WHERE requester_person_id = ?
                 ORDER BY updated_at DESC
                """, (rs, rowNum) -> suggestion(rs), requesterPersonId);
    }

    public List<SuggestionRow> allSuggestions() {
        return jdbcTemplate.query("SELECT " + SUGGESTION_COLUMNS + """
                  FROM app_module_suggestion
                 ORDER BY updated_at DESC
                """, (rs, rowNum) -> suggestion(rs));
    }

    public Optional<SuggestionRow> suggestion(UUID id) {
        return jdbcTemplate.query("SELECT " + SUGGESTION_COLUMNS + """
                  FROM app_module_suggestion WHERE id = ?
                """, (rs, rowNum) -> suggestion(rs), id).stream().findFirst();
    }

    public UUID insertSuggestion(String title, UUID requesterPersonId, String businessProblem,
                                 String goal, String scope, String dataScope,
                                 String permissionsNeeded, String costEstimate,
                                 String benefitEstimate, UUID suggestedOwnerPersonId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO app_module_suggestion
                    (tenant_id, title, requester_person_id, business_problem, goal, scope,
                     data_scope, permissions_needed, cost_estimate, benefit_estimate,
                     suggested_owner_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, title, requesterPersonId, businessProblem, goal, scope, dataScope,
                permissionsNeeded, costEstimate, benefitEstimate, suggestedOwnerPersonId);
    }

    public void updateStatus(UUID id, String status) {
        jdbcTemplate.update("""
                UPDATE app_module_suggestion SET status = ?, updated_at = now() WHERE id = ?
                """, status, id);
    }

    public void updateConfigOwner(UUID id, UUID configOwnerPersonId) {
        jdbcTemplate.update("""
                UPDATE app_module_suggestion
                   SET config_owner_person_id = ?, updated_at = now()
                 WHERE id = ?
                """, configOwnerPersonId, id);
    }

    private static final String EVENT_COLUMNS = """
            id, event_kind, from_status, to_status, action, actor_person_id,
            recipient_person_id, comment, occurred_at
            """;

    private static EventRow event(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventRow(rs.getObject("id", UUID.class), rs.getString("event_kind"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("action"),
                rs.getObject("actor_person_id", UUID.class),
                rs.getObject("recipient_person_id", UUID.class), rs.getString("comment"),
                rs.getObject("occurred_at", OffsetDateTime.class));
    }

    public List<EventRow> events(UUID suggestionId) {
        return jdbcTemplate.query("SELECT " + EVENT_COLUMNS + """
                  FROM app_module_suggestion_event
                 WHERE suggestion_id = ?
                 ORDER BY occurred_at, id
                """, (rs, rowNum) -> event(rs), suggestionId);
    }

    public void insertEvent(UUID suggestionId, String eventKind, String fromStatus,
                            String toStatus, String action, UUID actorPersonId,
                            UUID recipientPersonId, String comment) {
        jdbcTemplate.update("""
                INSERT INTO app_module_suggestion_event
                    (tenant_id, suggestion_id, event_kind, from_status, to_status, action,
                     actor_person_id, recipient_person_id, comment)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                """, suggestionId, eventKind, fromStatus, toStatus, action, actorPersonId,
                recipientPersonId, comment == null ? "" : comment);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
