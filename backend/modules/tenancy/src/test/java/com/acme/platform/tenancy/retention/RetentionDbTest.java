package com.acme.platform.tenancy.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.retention.DeletionRequest;
import com.acme.platform.platform.retention.DeletionRequestService;
import com.acme.platform.platform.retention.DeletionRequestStore;
import com.acme.platform.platform.retention.RetentionTarget;
import com.acme.platform.platform.retention.RetentionTombstonePort;
import com.acme.platform.platform.settings.PlatformSettingService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL 上的回收站、留置与到期清理闭环。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class RetentionDbTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    @Test
    void recycleHoldRestoreAndPurge() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantTransactions transactions = new TenantTransactions(jdbcTemplate, transactionTemplate);

        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenantId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

        PlatformSettingService settings = mock(PlatformSettingService.class);
        when(settings.intLimit("retention.recycle_bin_days", 30)).thenReturn(30);

        RetentionPolicy policy = new RetentionPolicy(settings);
        RetentionStore store = new RetentionStore(jdbcTemplate);
        LegalHoldStore holds = new LegalHoldStore(jdbcTemplate);
        RetentionEventStore events = new RetentionEventStore(jdbcTemplate, new ObjectMapper());
        FakeTarget target = new FakeTarget();
        @SuppressWarnings("unchecked")
        ObjectProvider<RetentionTombstonePort> tombstones = mock(ObjectProvider.class);

        RetentionService service = new RetentionService(store, holds, events, policy,
                List.of(target), tombstones, transactions, new ObjectMapper());

        RequestContext context = new RequestContext(tenantId, "tester", "rt-" + suffix, "trace-" + suffix);
        RequestContextHolder.set(context);

        UUID objectId = UUID.randomUUID();
        target.put(objectId, "objects/" + suffix + "/one.txt");

        RetentionBinItem item = service.recycle("file", objectId);
        assertThat(item.status()).isEqualTo("active");
        assertThat(item.objectRef()).isEqualTo("objects/" + suffix + "/one.txt");
        assertThat(item.purgeAfter()).isAfter(OffsetDateTime.now().plusDays(29));
        assertThat(target.recycled).contains(objectId);

        RetentionBinItem again = service.recycle("file", objectId);
        assertThat(again.id()).isEqualTo(item.id());

        assertThat(service.list(50)).extracting(RetentionBinItem::id).contains(item.id());

        transactions.inTenant(context, ctx -> jdbcTemplate.update("""
                INSERT INTO retention_legal_hold (tenant_id, data_class, reason, created_by)
                VALUES (?, 'file', '诉讼保全', 'legal')
                """, tenantId));
        UUID heldObject = UUID.randomUUID();
        target.put(heldObject, "objects/" + suffix + "/held.txt");
        assertThatThrownBy(() -> service.recycle("file", heldObject))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("RETENTION_LEGAL_HOLD");
                    assertThat(error.getMessage()).contains("诉讼保全");
                });
        // 解除类级留置，后续恢复/再删除流程不应再被阻止
        transactions.inTenant(context, ctx -> jdbcTemplate.update(
                "DELETE FROM retention_legal_hold WHERE tenant_id = ? AND data_class = 'file'", tenantId));

        assertThatThrownBy(() -> service.recycle("payroll_result", UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RETENTION_PROTECTED"));

        RetentionBinItem restored = service.restore(item.id());
        assertThat(restored.status()).isEqualTo("restored");
        assertThat(target.restored).contains(objectId);

        // 已恢复的条目不在回收站里，直接清空必须拒绝，避免「数据已删、状态还写着已恢复」
        assertThatThrownBy(() -> service.purgeNow(item.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RETENTION_ITEM_NOT_ACTIVE"));

        RetentionBinItem recycledAgain = service.recycle("file", objectId);
        assertThat(recycledAgain.id()).isNotEqualTo(item.id());
        RetentionBinItem purged = service.purgeNow(recycledAgain.id());
        assertThat(purged.status()).isEqualTo("purged");
        assertThat(target.purged).contains(objectId);

        assertThat(service.purgeNow(recycledAgain.id()).status()).isEqualTo("purged");

        Integer eventCount = transactions.inTenant(context, ctx -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retention_event WHERE tenant_id = ?", Integer.class, tenantId));
        assertThat(eventCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    void expiredItemsArePurgedInBatch() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantTransactions transactions = new TenantTransactions(jdbcTemplate, transactionTemplate);

        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenantId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        PlatformSettingService settings = mock(PlatformSettingService.class);
        when(settings.intLimit("retention.recycle_bin_days", 30)).thenReturn(30);
        RetentionService service = new RetentionService(
                new RetentionStore(jdbcTemplate),
                new LegalHoldStore(jdbcTemplate),
                new RetentionEventStore(jdbcTemplate, new ObjectMapper()),
                new RetentionPolicy(settings),
                List.of(new FakeTarget()),
                mock(ObjectProvider.class),
                transactions,
                new ObjectMapper());

        RequestContext context = new RequestContext(tenantId, "tester", "exp-" + suffix, "trace");
        UUID expired = UUID.randomUUID();
        transactions.inTenant(context, ctx -> jdbcTemplate.update("""
                INSERT INTO retention_bin_item
                    (tenant_id, data_class, object_id, snapshot, deleted_by, deleted_at, purge_after)
                VALUES (?, 'file', ?, '{}'::jsonb, 'tester',
                        now() - interval '40 days', now() - interval '10 days')
                """, tenantId, expired));

        assertThat(service.purgeDue(context, 10)).isGreaterThanOrEqualTo(1);
        String status = transactions.inTenant(context, ctx -> jdbcTemplate.queryForObject(
                "SELECT status FROM retention_bin_item WHERE object_id = ?", String.class, expired));
        assertThat(status).isEqualTo("purged");
    }

    @Test
    void deletionRequestCaseClosesAndRejectsIllegalTransitions() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        DeletionRequestService service =
                new DeletionRequestService(new DeletionRequestStore(new JdbcTemplate(dataSource)));
        UUID tenantId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID otherTenant = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        UUID subjectId = UUID.randomUUID();

        DeletionRequest request = service.submit(tenantId, "person", subjectId, "员工申请删除个人信息", "tenant-admin");
        assertThat(request.status()).isEqualTo("received");
        assertThat(request.subjectHash()).matches("^[0-9a-f]{64}$").doesNotContain(subjectId.toString());

        assertThat(service.startVerifying(request.id()).status()).isEqualTo("verifying");
        DeletionRequest approved = service.approve(request.id(), "APPROVED", "可匿名化，保留统计事实", "platform-ops");
        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.decidedBy()).isEqualTo("platform-ops");
        assertThat(service.startExecution(request.id(), "platform-ops").status()).isEqualTo("executing");
        DeletionRequest completed = service.complete(request.id());
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.executedAt()).isNotNull();

        assertThatThrownBy(() -> service.approve(request.id(), "APPROVED", null, "platform-ops"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DELETION_REQUEST_STATE_INVALID"));

        DeletionRequest second = service.submit(tenantId, "account", UUID.randomUUID(), "账号注销申请", "tenant-admin");
        assertThatThrownBy(() -> service.reject(second.id(), null, null, "platform-ops"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DELETION_REQUEST_DECISION_REQUIRED"));
        assertThatThrownBy(() -> service.cancel(second.id(), otherTenant, "tenant-admin"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DELETION_REQUEST_NOT_FOUND"));
        assertThat(service.cancel(second.id(), tenantId, "tenant-admin").status()).isEqualTo("cancelled");
        assertThatThrownBy(() -> service.startExecution(second.id(), "platform-ops"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DELETION_REQUEST_STATE_INVALID"));
    }

    private static final class FakeTarget implements RetentionTarget {

        private final Map<UUID, String> objects = new ConcurrentHashMap<>();
        private final Set<UUID> recycled = ConcurrentHashMap.newKeySet();
        private final Set<UUID> restored = ConcurrentHashMap.newKeySet();
        private final Set<UUID> purged = ConcurrentHashMap.newKeySet();

        void put(UUID id, String objectKey) {
            objects.put(id, objectKey);
        }

        @Override
        public String dataClass() {
            return "file";
        }

        @Override
        public Optional<Map<String, Object>> snapshot(UUID objectId) {
            return Optional.ofNullable(objects.get(objectId))
                    .map(objectKey -> Map.<String, Object>of("objectRef", objectKey, "fileName", "one.txt"));
        }

        @Override
        public void markRecycled(UUID objectId) {
            recycled.add(objectId);
        }

        @Override
        public void restore(UUID objectId) {
            restored.add(objectId);
        }

        @Override
        public void purge(UUID objectId) {
            purged.add(objectId);
            objects.remove(objectId);
        }
    }
}
