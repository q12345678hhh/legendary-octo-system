package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.identity.retention.AccountAnonymizer;
import com.acme.platform.organization.retention.PersonAnonymizer;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import com.acme.platform.platform.retention.DeletionRequest;
import com.acme.platform.platform.retention.DeletionRequestService;
import com.acme.platform.platform.retention.DeletionRequestStore;
import com.acme.platform.platform.retention.TombstoneStore;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 数据主体删除请求的执行闭环：在职拒绝、匿名化替换、墓碑与统计事实保留。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class DeletionAnonymizeDbTest {

    @Test
    void executionRejectsActiveEmploymentThenAnonymizesAndKeepsStatistics() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantTransactions transactions = new TenantTransactions(jdbcTemplate, transactionTemplate);

        DeletionRequestService requests = new DeletionRequestService(new DeletionRequestStore(jdbcTemplate));
        ObjectMapper objectMapper = new ObjectMapper();
        DataSubjectRequestOrchestrator orchestrator = new DataSubjectRequestOrchestrator(
                requests, new PersonAnonymizer(jdbcTemplate), new AccountAnonymizer(jdbcTemplate),
                new TombstoneStore(jdbcTemplate, objectMapper),
                transactions, new PlatformAuditStore(jdbcTemplate, objectMapper));

        UUID tenantId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID employmentId = UUID.randomUUID();
        RequestContext context = new RequestContext(tenantId, "tester",
                "dsr-" + System.nanoTime(), "trace");

        transactions.inTenant(context, ctx -> {
            jdbcTemplate.update("""
                    INSERT INTO organization_person (id, tenant_id, display_name, primary_email)
                    VALUES (?, ?, '张三', 'zhang@example.com')
                    """, personId, tenantId);
            jdbcTemplate.update("""
                    INSERT INTO identity_user_account
                        (id, tenant_id, email, email_normalized, display_name, password_hash, status)
                    VALUES (?, ?, 'zhang@example.com', 'zhang@example.com', '张三', '$2a$hash', 'active')
                    """, userId, tenantId);
            jdbcTemplate.update("""
                    INSERT INTO identity_tenant_member (tenant_id, user_id, person_id, status)
                    VALUES (?, ?, ?, 'active')
                    """, tenantId, userId, personId);
            jdbcTemplate.update("""
                    INSERT INTO organization_employment
                        (id, tenant_id, person_id, employment_no, status, effective_from)
                    VALUES (?, ?, ?, ?, 'active', ?)
                    """, employmentId, tenantId, personId, "E-" + employmentId, LocalDate.now().minusYears(1));
            return null;
        });

        DeletionRequest request = requests.submit(tenantId, "person", personId,
                "员工离职后申请删除个人信息", "tenant-admin");
        requests.approve(request.id(), "APPROVED", "可匿名化", "platform-ops");
        UUID operatorId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform_operator (id, email, display_name, role_code, password_hash)
                VALUES (?, ?, '隐私运营', 'platform-security', '$2a$hash')
                """, operatorId, "privacy-ops-" + operatorId + "@example.com");
        PlatformOperatorRef operator = new PlatformOperatorRef(operatorId, "privacy-ops@example.com");

        assertThatThrownBy(() -> orchestrator.execute(request.id(), operator))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DELETION_BLOCKED_ACTIVE_EMPLOYMENT"));
        assertThat(requests.get(request.id()).status()).isEqualTo("approved");

        transactions.inTenant(context, ctx -> {
            jdbcTemplate.update("UPDATE organization_employment SET status = 'ended' WHERE id = ?", employmentId);
            return null;
        });

        DeletionRequest completed = orchestrator.execute(request.id(), operator);
        assertThat(completed.status()).isEqualTo("completed");
        String hash8 = completed.subjectHash().substring(0, 8);

        transactions.inTenant(context, ctx -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT display_name FROM organization_person WHERE id = ?", String.class, personId))
                    .isEqualTo("匿名用户-" + hash8);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT primary_email FROM organization_person WHERE id = ?", String.class, personId))
                    .isNull();
            // 统计事实保留：账号仍在、状态未变，但不可再用旧凭据登录
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM identity_user_account WHERE id = ?", String.class, userId))
                    .isEqualTo("active");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT email FROM identity_user_account WHERE id = ?", String.class, userId))
                    .isEqualTo("anon-" + hash8 + "@invalid");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM identity_user_account WHERE id = ?", String.class, userId))
                    .isNull();
            return null;
        });

        Integer tombstones = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_deletion_tombstone WHERE request_id = ? AND action = 'anonymized'",
                Integer.class, request.id());
        assertThat(tombstones).isEqualTo(1);

        DeletionRequest missing = requests.submit(tenantId, "person", UUID.randomUUID(),
                "主体不存在的删除申请", "tenant-admin");
        requests.approve(missing.id(), "APPROVED", null, "platform-ops");
        DeletionRequest failed = orchestrator.execute(missing.id(), operator);
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.decisionNote()).isEqualTo("SUBJECT_NOT_FOUND");
    }
}
