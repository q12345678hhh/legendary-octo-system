package com.acme.platform.expense;

import com.acme.platform.expense.app.ExpenseAuditEvent;
import com.acme.platform.expense.app.ExpenseBatchHold;
import com.acme.platform.expense.app.ExpenseDocument;
import com.acme.platform.expense.app.ExpenseFinding;
import com.acme.platform.expense.app.ExpensePolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 单据持久层。调用方必须先绑定租户上下文。
 */
@Component
public class ExpenseStore {

    private static final String DOCUMENT_COLUMNS = """
            id, uploader_person_id, file_id, doc_type, status, currency, amount,
            invoice_code, invoice_no, vendor_name, vendor_tax_no, occurred_on, category,
            findings::text AS findings, confidence::text AS confidence,
            rule_version, policy_version, extraction_id, posted_at, posted_by, post_actor_kind,
            voided_at, voided_by, void_reason, created_at
            """;

    private static final String POLICY_COLUMNS = """
            auto_post_enabled, auto_post_limit, window_days, legal_title, tax_id,
            vendor_allowlist::text AS vendor_allowlist,
            manual_categories::text AS manual_categories,
            allowed_categories::text AS allowed_categories,
            min_confidence, verify_required, version
            """;

    private static final String HOLD_COLUMNS = """
            id, scope_type, scope_key, reason, window_hours, document_count, amount_total,
            status, created_by, created_at, released_by, released_at, release_note
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExpenseStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 单据

    public ExpenseDocument insertDocument(UUID tenantId, UUID id, UUID uploaderPersonId,
                                          UUID fileId, String docType, String currency,
                                          BigDecimal amount, String invoiceCode, String invoiceNo,
                                          String vendorName, String vendorTaxNo,
                                          LocalDate occurredOn, String category,
                                          String findingsJson, String confidenceJson,
                                          String ruleVersion, int policyVersion) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO expense_document
                    (id, tenant_id, uploader_person_id, file_id, doc_type, status, currency, amount,
                     invoice_code, invoice_no, vendor_name, vendor_tax_no, occurred_on, category,
                     findings, confidence, rule_version, policy_version)
                VALUES (?, ?, ?, ?, ?, 'manual_review', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                RETURNING %s
                """.formatted(DOCUMENT_COLUMNS),
                mapper(), id, tenantId, uploaderPersonId, fileId, docType, currency, amount,
                invoiceCode, invoiceNo, vendorName, vendorTaxNo, occurredOn, category,
                findingsJson, confidenceJson, ruleVersion, policyVersion);
    }

    public void updateAfterPreReview(UUID id, String status, List<ExpenseFinding> findings,
                                     Map<String, Double> confidence, String ruleVersion,
                                     int policyVersion, UUID extractionId) {
        jdbcTemplate.update("""
                UPDATE expense_document
                   SET status = ?, findings = ?::jsonb, confidence = ?::jsonb,
                       rule_version = ?, policy_version = ?, extraction_id = ?, updated_at = now()
                 WHERE id = ?
                """, status, write(findings), write(confidence), ruleVersion, policyVersion,
                extractionId, id);
    }

    public void markStatus(UUID id, String status) {
        jdbcTemplate.update("""
                UPDATE expense_document SET status = ?, updated_at = now() WHERE id = ?
                """, status, id);
    }

    public void markPosted(UUID id, UUID postedBy, String actorKind) {
        jdbcTemplate.update("""
                UPDATE expense_document
                   SET status = 'posted', posted_at = now(), posted_by = ?,
                       post_actor_kind = ?, updated_at = now()
                 WHERE id = ?
                """, postedBy, actorKind, id);
    }

    public void markVoided(UUID id, UUID voidedBy, String reason) {
        jdbcTemplate.update("""
                UPDATE expense_document
                   SET status = 'voided', voided_at = now(), voided_by = ?, void_reason = ?,
                       updated_at = now()
                 WHERE id = ?
                """, voidedBy, reason, id);
    }

    public Optional<ExpenseDocument> find(UUID id) {
        return jdbcTemplate.query("SELECT " + DOCUMENT_COLUMNS
                + " FROM expense_document WHERE id = ?", mapper(), id).stream().findFirst();
    }

    public List<ExpenseDocument> list(UUID uploaderPersonId, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + DOCUMENT_COLUMNS
                + " FROM expense_document WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (uploaderPersonId != null) {
            sql.append(" AND uploader_person_id = ?");
            args.add(uploaderPersonId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), mapper(), args.toArray());
    }

    /** 是否已有同哈希文件的有效单据（驳回/撤销的不算）。 */
    public boolean sameFileSubmitted(String sha256, UUID excludeDocumentId) {
        if (sha256 == null || sha256.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM expense_document d
                  JOIN platform_file f ON f.id = d.file_id
                 WHERE f.sha256 = ?
                   AND d.id <> ?
                   AND d.status NOT IN ('rejected', 'voided')
                """, Integer.class, sha256.toLowerCase(), excludeDocumentId);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------ 抽取

    public void insertExtraction(UUID tenantId, UUID id, UUID documentId, String provider,
                                 String model, String status, String ocrText, String fieldsJson,
                                 String confidenceJson, long latencyMs, String errorCode) {
        jdbcTemplate.update("""
                INSERT INTO expense_document_extraction
                    (id, tenant_id, document_id, provider, model, status, ocr_text, fields,
                     confidence, latency_ms, error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, id, tenantId, documentId, provider, model, status, ocrText, fieldsJson,
                confidenceJson, (int) latencyMs, errorCode);
    }

    public Optional<ExpenseDocument.ExtractionSummary> extraction(UUID documentId) {
        return jdbcTemplate.query("""
                SELECT id, status, provider, model, error_code, ocr_text, created_at
                  FROM expense_document_extraction
                 WHERE document_id = ?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, (rs, rowNum) -> new ExpenseDocument.ExtractionSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getString("error_code"),
                        rs.getString("ocr_text"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                documentId).stream().findFirst();
    }

    // ------------------------------------------------------------------ 复核

    public void insertReview(UUID tenantId, UUID id, UUID documentId, String decision,
                             String comment, String overridesJson, UUID actorPersonId) {
        jdbcTemplate.update("""
                INSERT INTO expense_document_review
                    (id, tenant_id, document_id, decision, comment, overrides, actor_person_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, tenantId, documentId, decision, comment, overridesJson, actorPersonId);
    }

    // ------------------------------------------------------------------ 批量异常暂停

    /** 24 小时窗口内同供应商 / 同上传人 / 金额相近的单据数量（排除已撤销与当前单据）。 */
    public record BatchCounts(int vendorCount, int uploaderCount, int amountCount) {
    }

    public BatchCounts batchCounts(String vendorKey, UUID uploaderPersonId, BigDecimal amount,
                                   BigDecimal tolerance, int windowHours,
                                   UUID excludeDocumentId) {
        BigDecimal low = amount == null ? null
                : amount.multiply(BigDecimal.ONE.subtract(tolerance));
        BigDecimal high = amount == null ? null
                : amount.multiply(BigDecimal.ONE.add(tolerance));
        return jdbcTemplate.queryForObject("""
                SELECT
                    count(*) FILTER (WHERE ? <> ''
                                       AND lower(btrim(coalesce(vendor_name, ''))) = ?),
                    count(*) FILTER (WHERE uploader_person_id = ?),
                    count(*) FILTER (WHERE ?::numeric IS NOT NULL AND amount BETWEEN ? AND ?)
                  FROM expense_document
                 WHERE created_at >= now() - make_interval(hours => ?)
                   AND status <> 'voided'
                   AND id <> ?
                """, (rs, rowNum) -> new BatchCounts(
                        rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                vendorKey, vendorKey, uploaderPersonId, low, low, high, windowHours,
                excludeDocumentId);
    }

    /** 同范围已有 active 暂停时不再重复插入。 */
    public boolean insertHoldIfAbsent(UUID tenantId, String scopeType, String scopeKey,
                                      String reason, int windowHours, int documentCount,
                                      BigDecimal amountTotal, UUID createdBy) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO expense_batch_hold
                    (tenant_id, scope_type, scope_key, reason, window_hours, document_count,
                     amount_total, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, tenantId, scopeType, scopeKey, reason, windowHours, documentCount,
                amountTotal, createdBy);
        return inserted == 1;
    }

    public String activeHoldReason(UUID tenantId, String scopeType, String scopeKey) {
        return jdbcTemplate.query("""
                SELECT reason
                  FROM expense_batch_hold
                 WHERE tenant_id = ? AND scope_type = ? AND scope_key = ? AND status = 'active'
                """, (rs, rowNum) -> rs.getString("reason"), tenantId, scopeType, scopeKey)
                .stream().findFirst().orElse(null);
    }

    public Optional<ExpenseBatchHold> findHold(UUID id) {
        return jdbcTemplate.query("SELECT " + HOLD_COLUMNS
                + " FROM expense_batch_hold WHERE id = ?", holdMapper(), id)
                .stream().findFirst();
    }

    public List<ExpenseBatchHold> listHolds(String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + HOLD_COLUMNS
                + " FROM expense_batch_hold WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), holdMapper(), args.toArray());
    }

    public boolean releaseHold(UUID tenantId, UUID holdId, UUID releasedBy, String note) {
        return jdbcTemplate.update("""
                UPDATE expense_batch_hold
                   SET status = 'released', released_by = ?, released_at = now(), release_note = ?
                 WHERE id = ? AND tenant_id = ? AND status = 'active'
                """, releasedBy, note, holdId, tenantId) == 1;
    }

    /** 最近一次人工复核人；没有复核记录时返回 null。 */
    public UUID lastReviewer(UUID documentId) {
        return jdbcTemplate.query("""
                SELECT actor_person_id
                  FROM expense_document_review
                 WHERE document_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """, (rs, rowNum) -> rs.getObject("actor_person_id", UUID.class), documentId)
                .stream().findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ 发票号占用

    /** 占用成功返回 true；已有活动占用（含并发）返回 false，由规则转人工。 */
    public boolean claimInvoice(UUID tenantId, UUID documentId, String invoiceCode,
                                String invoiceNo) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO expense_invoice_claim
                    (tenant_id, document_id, invoice_code, invoice_no)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, tenantId, documentId, invoiceCode == null ? "" : invoiceCode, invoiceNo);
        return inserted == 1;
    }

    public void releaseClaim(UUID documentId, String reason) {
        jdbcTemplate.update("""
                UPDATE expense_invoice_claim
                   SET released_at = now(), release_reason = ?
                 WHERE document_id = ? AND released_at IS NULL
                """, reason, documentId);
    }

    // ------------------------------------------------------------------ 审计

    public void insertAudit(UUID tenantId, UUID documentId, String action, UUID actorPersonId,
                            String actorKind, String detailJson) {
        jdbcTemplate.update("""
                INSERT INTO expense_audit_event
                    (tenant_id, document_id, action, actor_person_id, actor_kind, detail)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, tenantId, documentId, action, actorPersonId, actorKind, detailJson);
    }

    public List<ExpenseAuditEvent> audit(UUID documentId) {
        return jdbcTemplate.query("""
                SELECT id, document_id, action, actor_person_id, actor_kind,
                       detail::text AS detail, occurred_at
                  FROM expense_audit_event
                 WHERE document_id = ?
                 ORDER BY occurred_at, id
                """, (rs, rowNum) -> new ExpenseAuditEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("action"),
                        rs.getObject("actor_person_id", UUID.class),
                        rs.getString("actor_kind"),
                        readMap(rs.getString("detail")),
                        rs.getObject("occurred_at", OffsetDateTime.class)),
                documentId);
    }

    // ------------------------------------------------------------------ 租户策略

    public ExpensePolicy policy(UUID tenantId) {
        return jdbcTemplate.query("SELECT " + POLICY_COLUMNS
                        + " FROM expense_tenant_policy WHERE tenant_id = ?",
                policyMapper(), tenantId).stream().findFirst().orElse(ExpensePolicy.defaults());
    }

    public ExpensePolicy upsertPolicy(UUID tenantId, ExpensePolicy input) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO expense_tenant_policy
                    (tenant_id, auto_post_enabled, auto_post_limit, window_days, legal_title,
                     tax_id, vendor_allowlist, manual_categories, allowed_categories,
                     min_confidence, verify_required, version)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, 1)
                ON CONFLICT (tenant_id) DO UPDATE
                   SET auto_post_enabled = EXCLUDED.auto_post_enabled,
                       auto_post_limit = EXCLUDED.auto_post_limit,
                       window_days = EXCLUDED.window_days,
                       legal_title = EXCLUDED.legal_title,
                       tax_id = EXCLUDED.tax_id,
                       vendor_allowlist = EXCLUDED.vendor_allowlist,
                       manual_categories = EXCLUDED.manual_categories,
                       allowed_categories = EXCLUDED.allowed_categories,
                       min_confidence = EXCLUDED.min_confidence,
                       verify_required = EXCLUDED.verify_required,
                       version = expense_tenant_policy.version + 1,
                       updated_at = now()
                RETURNING %s
                """.formatted(POLICY_COLUMNS),
                policyMapper(), tenantId, input.autoPostEnabled(), input.autoPostLimit(),
                input.windowDays(), emptyToNull(input.legalTitle()), emptyToNull(input.taxId()),
                write(input.vendorAllowlist()), write(input.manualCategories()),
                write(input.allowedCategories()), input.minConfidence(), input.verifyRequired());
    }

    // ------------------------------------------------------------------ 映射

    private RowMapper<ExpenseDocument> mapper() {
        return (rs, rowNum) -> new ExpenseDocument(
                rs.getObject("id", UUID.class),
                rs.getObject("uploader_person_id", UUID.class),
                rs.getObject("file_id", UUID.class),
                rs.getString("doc_type"),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getBigDecimal("amount"),
                rs.getString("invoice_code"),
                rs.getString("invoice_no"),
                rs.getString("vendor_name"),
                rs.getString("vendor_tax_no"),
                rs.getObject("occurred_on", LocalDate.class),
                rs.getString("category"),
                readFindings(rs.getString("findings")),
                readConfidence(rs.getString("confidence")),
                rs.getString("rule_version"),
                rs.getInt("policy_version"),
                rs.getObject("extraction_id", UUID.class),
                rs.getObject("posted_at", OffsetDateTime.class),
                rs.getObject("posted_by", UUID.class),
                rs.getString("post_actor_kind"),
                rs.getObject("voided_at", OffsetDateTime.class),
                rs.getObject("voided_by", UUID.class),
                rs.getString("void_reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private RowMapper<ExpensePolicy> policyMapper() {
        return (rs, rowNum) -> new ExpensePolicy(
                rs.getBoolean("auto_post_enabled"),
                rs.getBigDecimal("auto_post_limit"),
                rs.getInt("window_days"),
                rs.getString("legal_title"),
                rs.getString("tax_id"),
                readList(rs.getString("vendor_allowlist")),
                readList(rs.getString("manual_categories")),
                readList(rs.getString("allowed_categories")),
                rs.getBigDecimal("min_confidence"),
                rs.getBoolean("verify_required"),
                rs.getInt("version"));
    }

    private RowMapper<ExpenseBatchHold> holdMapper() {
        return (rs, rowNum) -> new ExpenseBatchHold(
                rs.getObject("id", UUID.class),
                rs.getString("scope_type"),
                rs.getString("scope_key"),
                rs.getString("reason"),
                rs.getInt("window_hours"),
                rs.getInt("document_count"),
                rs.getBigDecimal("amount_total"),
                rs.getString("status"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("released_by", UUID.class),
                rs.getObject("released_at", OffsetDateTime.class),
                rs.getString("release_note"));
    }

    private List<ExpenseFinding> readFindings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<List<ExpenseFinding>>() { });
    }

    private Map<String, Double> readConfidence(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() { });
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<List<String>>() { });
    }

    private String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
