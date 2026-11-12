package com.acme.platform.organization;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 自然人的 JDBC 存储：只写 SQL，事务由调用方的 {@code TenantTransactions.inTenant(...)} 提供。
 *
 * <p>目录类查询必须带 {@link DirectoryScope}，范围条件在 SQL 里完成，禁止先查全量再内存过滤。
 */
@Component
public class PersonStore {

    private static final RowMapper<Person> MAPPER = (rs, rowNum) -> new Person(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("display_name"),
            rs.getString("primary_email"),
            PersonStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT p.id, p.tenant_id, p.display_name, p.primary_email, p.status, p.created_at
              FROM organization_person p
            """;

    private final JdbcTemplate jdbcTemplate;

    public PersonStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Person> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, display_name, primary_email, status, created_at
                  FROM organization_person
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Person> list(int limit) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, display_name, primary_email, status, created_at
                  FROM organization_person
                 ORDER BY created_at DESC
                 LIMIT ?
                """, MAPPER, limit);
    }

    public List<Person> listInScope(DirectoryScope scope, String query, int limit) {
        ScopedSql scoped = scopedSql(scope);
        if (scoped == null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1")
                .append(scoped.sql());
        List<Object> args = new ArrayList<>(scoped.args());
        if (query != null && !query.isBlank()) {
            sql.append(" AND p.display_name ILIKE ?");
            args.add("%" + query.trim() + "%");
        }
        sql.append(" ORDER BY p.created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<Person> listInScopeByIds(Collection<UUID> ids, DirectoryScope scope, int limit) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        ScopedSql scoped = scopedSql(scope);
        if (scoped == null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1")
                .append(scoped.sql());
        List<Object> args = new ArrayList<>(scoped.args());
        sql.append(" AND p.id = ANY (string_to_array(?, ',')::uuid[])");
        args.add(ids.stream().map(UUID::toString).collect(Collectors.joining(",")));
        sql.append(" ORDER BY p.created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<Person> findInScope(UUID id, DirectoryScope scope) {
        List<Person> found = listInScopeByIds(List.of(id), scope, 1);
        return found.stream().findFirst();
    }

    public boolean existsInScope(UUID id, DirectoryScope scope) {
        return findInScope(id, scope).isPresent();
    }

    private record ScopedSql(String sql, List<Object> args) {
    }

    /**
     * 租户范围返回空条件；空范围返回 null，调用方据此直接返回空列表。
     * 部门范围要求当前生效主岗落在可见部门内。
     */
    private ScopedSql scopedSql(DirectoryScope scope) {
        if (scope == null || scope.empty()) {
            return null;
        }
        if (scope.tenantWide()) {
            return new ScopedSql("", List.of());
        }
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!scope.personIds().isEmpty()) {
            parts.add("p.id = ANY (string_to_array(?, ',')::uuid[])");
            args.add(scope.personIds().stream().map(UUID::toString)
                    .collect(Collectors.joining(",")));
        }
        if (!scope.orgUnitIds().isEmpty()) {
            parts.add("""
                    EXISTS (
                        SELECT 1
                          FROM organization_employment e
                          JOIN organization_employment_assignment a
                            ON a.employment_id = e.id AND a.tenant_id = e.tenant_id
                          JOIN organization_position pos
                            ON pos.id = a.position_id AND pos.tenant_id = a.tenant_id
                         WHERE e.person_id = p.id
                           AND e.status = 'active'
                           AND a.assignment_type = 'primary'
                           AND a.effective_from <= ?
                           AND (a.effective_to IS NULL OR a.effective_to > ?)
                           AND pos.org_unit_id = ANY (string_to_array(?, ',')::uuid[])
                    )""");
            LocalDate today = LocalDate.now();
            args.add(today);
            args.add(today);
            args.add(scope.orgUnitIds().stream().map(UUID::toString)
                    .collect(Collectors.joining(",")));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return new ScopedSql(" AND (" + String.join(" OR ", parts) + ")", args);
    }

    public boolean exists(UUID id) {
        Boolean found = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM organization_person WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(found);
    }

    public void insert(UUID id, String displayName, String primaryEmail) {
        jdbcTemplate.update("""
                INSERT INTO organization_person (id, tenant_id, display_name, primary_email)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                """, id, displayName, primaryEmail);
    }
}
