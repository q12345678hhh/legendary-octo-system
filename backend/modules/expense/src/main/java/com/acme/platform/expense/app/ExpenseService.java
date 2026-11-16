package com.acme.platform.expense.app;

import com.acme.platform.expense.ExpenseRuleEngine;
import com.acme.platform.expense.ExpenseStore;

import com.acme.platform.expense.ocr.DocumentExtractor;
import com.acme.platform.expense.ocr.ExtractedFields;
import com.acme.platform.expense.ocr.Extraction;
import com.acme.platform.expense.ocr.OcrProperties;
import com.acme.platform.expense.ocr.OcrStatus;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.file.FileRecord;
import com.acme.platform.platform.file.FileScanService;
import com.acme.platform.platform.file.FileService;
import com.acme.platform.platform.storage.ObjectStorageClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 单据预审应用服务。
 *
 * <p>外部调用（对象存储下载、OCR）在事务外完成；写库、规则判定与审计在同一租户事务内，
 * 保证「单据状态、发票号占用、审计事件」要么一起成功，要么一起回滚。
 *
 * <p>规则不通过、OCR 不可用或输出非法时一律落 {@code manual_review}，
 * 任何失败都不会被默认当成通过。
 */
@Service
public class ExpenseService {

    private static final int MAX_LIST = 100;
    private static final String STATUS_MANUAL = "manual_review";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_POSTED = "posted";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_VOIDED = "voided";

    /** 批量异常阈值：固定常量，避免把运营阈值写进租户策略与契约。 */
    private static final int BATCH_WINDOW_HOURS = 24;
    private static final int VENDOR_THRESHOLD = 5;
    private static final int UPLOADER_THRESHOLD = 10;
    private static final int AMOUNT_THRESHOLD = 5;
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.05");

    public record CreateInput(UUID fileId, String docType, ExtractedFields manualFields) {
    }

    public record Override(String ruleCode, String reason) {
    }

    public record DocumentView(ExpenseDocument document,
                               ExpenseDocument.ExtractionSummary extraction) {
    }

    public record PolicyInput(boolean autoPostEnabled, BigDecimal autoPostLimit, int windowDays,
                              String legalTitle, String taxId, List<String> vendorAllowlist,
                              List<String> manualCategories, List<String> allowedCategories,
                              BigDecimal minConfidence, boolean verifyRequired) {
    }

    private final ExpenseStore store;
    private final ExpenseRuleEngine rules;
    private final TenantTransactions transactions;
    private final FileService files;
    private final FileScanService scanner;
    private final ObjectStorageClient storage;
    private final DocumentExtractor extractor;
    private final OcrProperties ocrProperties;
    private final ObjectMapper objectMapper;

    public ExpenseService(ExpenseStore store, ExpenseRuleEngine rules,
                          TenantTransactions transactions, FileService files,
                          FileScanService scanner, ObjectStorageClient storage,
                          DocumentExtractor extractor, OcrProperties ocrProperties,
                          ObjectMapper objectMapper) {
        this.store = store;
        this.rules = rules;
        this.transactions = transactions;
        this.files = files;
        this.scanner = scanner;
        this.storage = storage;
        this.extractor = extractor;
        this.ocrProperties = ocrProperties;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 提交与预审

    public DocumentView submit(UUID personId, String actorSubjectId, CreateInput input) {
        if (input == null || input.fileId() == null) {
            throw ApiException.badRequest("EXPENSE_FILE_REQUIRED", "必须选择已上传的凭证文件");
        }
        String docType = input.docType() == null ? "" : input.docType().strip();
        if (!List.of("invoice", "receipt", "bill").contains(docType)) {
            throw ApiException.badRequest("EXPENSE_DOC_TYPE_INVALID",
                    "单据类型只能是 invoice、receipt 或 bill");
        }
        FileRecord file = requireOwnedFile(input.fileId(), actorSubjectId);
        if (!file.clean() || !file.available()) {
            UUID fileId = file.id();
            transactions.inTenant(context -> {
                scanner.scan(fileId);
                return null;
            });
            file = files.find(fileId);
        }
        if (!file.clean() || !file.available()) {
            throw ApiException.conflict("EXPENSE_FILE_NOT_READY",
                    "文件尚未通过安全扫描，暂不能提交预审");
        }

        Extraction extraction = input.manualFields() != null
                ? Extraction.manual(input.manualFields())
                : extract(file);
        ExtractedFields fields = extraction.fields();
        UUID documentId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();

        FileRecord scanned = file;
        return transactions.inTenant(context -> {
            UUID tenantId = context.tenantId();
            ExpensePolicy policy = store.policy(tenantId);
            store.insertDocument(tenantId, documentId, personId, scanned.id(), docType,
                    fields.currency(), fields.amount(), fields.invoiceCode(), fields.invoiceNo(),
                    fields.vendorName(), fields.vendorTaxNo(), fields.occurredOn(),
                    fields.category(), "[]", write(extraction.confidence()),
                    ExpenseRuleEngine.RULE_VERSION, policy.version());
            store.insertExtraction(tenantId, extractionId, documentId, extraction.provider(),
                    extraction.model(), extraction.status().name(), extraction.ocrText(),
                    write(fields), write(extraction.confidence()), extraction.latencyMs(),
                    extraction.errorCode());

            boolean invoiceClaimed = false;
            if ("invoice".equals(docType) && fields.invoiceNo() != null
                    && !fields.invoiceNo().isBlank()) {
                invoiceClaimed = !store.claimInvoice(tenantId, documentId,
                        fields.invoiceCode(), fields.invoiceNo());
            }
            boolean sameFile = store.sameFileSubmitted(scanned.sha256(), documentId);
            ExpenseRuleEngine.BatchSignals batch = batchSignals(tenantId, personId, documentId,
                    fields);
            ExpenseRuleEngine.Decision decision = rules.evaluate(extraction, policy,
                    new ExpenseRuleEngine.Duplicates(invoiceClaimed, sameFile), batch, docType,
                    LocalDate.now());

            store.updateAfterPreReview(documentId, STATUS_MANUAL, decision.findings(),
                    extraction.confidence(), decision.ruleVersion(), decision.policyVersion(),
                    extractionId);
            store.insertAudit(tenantId, documentId, "submitted", personId, "employee",
                    write(Map.of("docType", docType, "fileId", scanned.id().toString())));
            store.insertAudit(tenantId, documentId, "pre_review.completed", null, "ai",
                    write(Map.of(
                            "extractionId", extractionId.toString(),
                            "extractionStatus", extraction.status().name(),
                            "provider", extraction.provider() == null ? "" : extraction.provider(),
                            "model", extraction.model() == null ? "" : extraction.model(),
                            "promptVersion", ocrProperties.promptVersion(),
                            "ruleVersion", decision.ruleVersion(),
                            "policyVersion", decision.policyVersion(),
                            "autoPost", decision.autoPost())));
            if (decision.autoPost()) {
                store.markPosted(documentId, null, "ai");
                store.insertAudit(tenantId, documentId, "auto_posted", null, "ai",
                        write(Map.of(
                                "amount", fields.amount() == null ? "" : fields.amount(),
                                "currency", fields.currency() == null ? "" : fields.currency(),
                                "category", fields.category() == null ? "" : fields.category())));
            }
            return view(store.find(documentId).orElseThrow());
        });
    }

    /** 识别批量异常并落暂停记录；当前单据也计入阈值。 */
    private ExpenseRuleEngine.BatchSignals batchSignals(UUID tenantId, UUID uploaderPersonId,
                                                        UUID documentId, ExtractedFields fields) {
        String vendorKey = fields.vendorName() == null ? ""
                : fields.vendorName().strip().toLowerCase(Locale.ROOT);
        BigDecimal amount = fields.amount();
        String amountKey = amount == null ? null
                : "amount:" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        ExpenseStore.BatchCounts counts = store.batchCounts(vendorKey, uploaderPersonId, amount,
                AMOUNT_TOLERANCE, BATCH_WINDOW_HOURS, documentId);

        int vendorCount = counts.vendorCount() + 1;
        int uploaderCount = counts.uploaderCount() + 1;
        int amountCount = counts.amountCount() + 1;
        boolean vendorHit = !vendorKey.isEmpty() && vendorCount >= VENDOR_THRESHOLD;
        boolean uploaderHit = uploaderCount >= UPLOADER_THRESHOLD;
        boolean amountHit = amountKey != null && amountCount >= AMOUNT_THRESHOLD;

        if (vendorHit) {
            holdBatch(tenantId, "vendor", vendorKey, "24 小时内同一供应商提交 " + vendorCount
                    + " 张单据，达到阈值 " + VENDOR_THRESHOLD + " 张", vendorCount, null,
                    uploaderPersonId, documentId);
        }
        if (uploaderHit) {
            holdBatch(tenantId, "uploader", uploaderPersonId.toString(),
                    "24 小时内同一上传人提交 " + uploaderCount + " 张单据，达到阈值 "
                            + UPLOADER_THRESHOLD + " 张", uploaderCount, null,
                    uploaderPersonId, documentId);
        }
        if (amountHit) {
            holdBatch(tenantId, "amount", amountKey, "24 小时内金额相近（±5%）的单据达到 "
                    + amountCount + " 张，阈值为 " + AMOUNT_THRESHOLD + " 张", amountCount,
                    amount, uploaderPersonId, documentId);
        }

        String holdReason = firstHold(tenantId, "vendor", vendorKey);
        if (holdReason == null) {
            holdReason = firstHold(tenantId, "uploader", uploaderPersonId.toString());
        }
        if (holdReason == null) {
            holdReason = firstHold(tenantId, "amount", amountKey);
        }
        return new ExpenseRuleEngine.BatchSignals(vendorHit, uploaderHit, amountHit, holdReason);
    }

    private void holdBatch(UUID tenantId, String scopeType, String scopeKey, String reason,
                           int documentCount, BigDecimal amountTotal, UUID createdBy,
                           UUID documentId) {
        boolean created = store.insertHoldIfAbsent(tenantId, scopeType, scopeKey, reason,
                BATCH_WINDOW_HOURS, documentCount, amountTotal, createdBy);
        if (created) {
            store.insertAudit(tenantId, null, "batch_anomaly.detected", createdBy, "ai",
                    write(Map.of(
                            "scopeType", scopeType,
                            "scopeKey", scopeKey,
                            "documentCount", documentCount,
                            "windowHours", BATCH_WINDOW_HOURS,
                            "documentId", documentId.toString())));
        }
    }

    private String firstHold(UUID tenantId, String scopeType, String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return null;
        }
        String reason = store.activeHoldReason(tenantId, scopeType, scopeKey);
        return reason == null || reason.isBlank() ? null : reason;
    }

    private FileRecord requireOwnedFile(UUID fileId, String actorSubjectId) {
        FileRecord file = files.find(fileId);
        if (actorSubjectId == null || !actorSubjectId.equals(file.subjectId())) {
            throw ApiException.forbidden("EXPENSE_FILE_NOT_OWNED", "只能提交本人上传的凭证文件");
        }
        return file;
    }

    private Extraction extract(FileRecord file) {
        if (!extractor.configured()) {
            return Extraction.failure(OcrStatus.UNAVAILABLE, "OCR_NOT_CONFIGURED", 0);
        }
        byte[] content;
        try {
            content = storage.download(file.objectKey(), ocrProperties.maxBytes());
        } catch (RuntimeException e) {
            return Extraction.failure(OcrStatus.UNAVAILABLE, "STORAGE_UNAVAILABLE", 0);
        }
        return extractor.extract(content, file.contentType(), file.fileName());
    }

    // ------------------------------------------------------------------ 查询

    public List<DocumentView> list(UUID personId, boolean canReview, String status, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIST));
        return transactions.inTenant(context -> store
                .list(canReview ? null : personId, status, bounded)
                .stream().map(this::view).toList());
    }

    public DocumentView detail(UUID personId, boolean canReview, UUID documentId) {
        return transactions.inTenant(context -> {
            ExpenseDocument document = requireVisible(personId, canReview, documentId);
            return view(document);
        });
    }

    public List<ExpenseAuditEvent> audit(UUID personId, boolean canReview, UUID documentId) {
        return transactions.inTenant(context -> {
            requireVisible(personId, canReview, documentId);
            return store.audit(documentId);
        });
    }

    private ExpenseDocument requireVisible(UUID personId, boolean canReview, UUID documentId) {
        ExpenseDocument document = store.find(documentId)
                .orElseThrow(() -> ApiException.notFound("EXPENSE_DOCUMENT_NOT_FOUND",
                        "单据不存在：" + documentId));
        if (!canReview && !document.uploaderPersonId().equals(personId)) {
            throw ApiException.forbidden("EXPENSE_DOCUMENT_FORBIDDEN", "只能查看本人的单据");
        }
        return document;
    }

    private DocumentView view(ExpenseDocument document) {
        return new DocumentView(document, store.extraction(document.id()).orElse(null));
    }

    // ------------------------------------------------------------------ 人工复核

    public DocumentView review(UUID reviewerPersonId, UUID documentId, String decision,
                               String comment, List<Override> overrides) {
        String action = decision == null ? "" : decision.strip();
        if (!List.of("approve", "reject").contains(action)) {
            throw ApiException.badRequest("EXPENSE_REVIEW_ACTION_INVALID",
                    "复核动作只能是 approve 或 reject");
        }
        String note = comment == null ? "" : comment.strip();
        if ("reject".equals(action) && note.isEmpty()) {
            throw ApiException.badRequest("EXPENSE_REJECT_COMMENT_REQUIRED", "驳回必须填写原因");
        }
        List<Override> safeOverrides = overrides == null ? List.of() : overrides;
        return transactions.inTenant(context -> {
            ExpenseDocument document = requireVisible(reviewerPersonId, true, documentId);
            if (document.uploaderPersonId().equals(reviewerPersonId)) {
                throw ApiException.forbidden("EXPENSE_SOD_VIOLATION", "不能复核自己提交的单据");
            }
            if (!STATUS_MANUAL.equals(document.status())) {
                throw ApiException.conflict("EXPENSE_STATE_INVALID",
                        "只有待复核单据可以复核，当前状态：" + document.status());
            }
            store.insertReview(context.tenantId(), UUID.randomUUID(), documentId, action, note,
                    write(safeOverrides), reviewerPersonId);
            if ("reject".equals(action)) {
                store.markStatus(documentId, STATUS_REJECTED);
                store.releaseClaim(documentId, "rejected");
            } else {
                store.markStatus(documentId, STATUS_APPROVED);
            }
            store.insertAudit(context.tenantId(), documentId, "review.decided", reviewerPersonId,
                    "reviewer", write(Map.of(
                            "decision", action,
                            "comment", note,
                            "overrides", safeOverrides)));
            return view(store.find(documentId).orElseThrow());
        });
    }

    public DocumentView post(UUID posterPersonId, UUID documentId) {
        return transactions.inTenant(context -> {
            ExpenseDocument document = requireVisible(posterPersonId, true, documentId);
            if (document.uploaderPersonId().equals(posterPersonId)) {
                throw ApiException.forbidden("EXPENSE_SOD_VIOLATION", "入账人不能是提交人");
            }
            UUID reviewer = store.lastReviewer(documentId);
            if (reviewer != null && reviewer.equals(posterPersonId)) {
                throw ApiException.forbidden("EXPENSE_SOD_VIOLATION", "入账人不能是复核人");
            }
            if (!STATUS_APPROVED.equals(document.status())) {
                throw ApiException.conflict("EXPENSE_STATE_INVALID",
                        "只有已通过待入账的单据可以入账，当前状态：" + document.status());
            }
            if (document.invoiceNo() != null && !document.invoiceNo().isBlank()
                    && !store.claimInvoice(context.tenantId(), documentId,
                    document.invoiceCode(), document.invoiceNo())) {
                throw ApiException.conflict("EXPENSE_INVOICE_CLAIMED",
                        "该发票号已被其他有效单据占用，不能入账");
            }
            store.markPosted(documentId, posterPersonId, "employee");
            store.insertAudit(context.tenantId(), documentId, "posted", posterPersonId,
                    "poster", write(Map.of(
                            "amount", document.amount() == null ? "" : document.amount(),
                            "currency", document.currency() == null ? "" : document.currency(),
                            "category", document.category() == null ? "" : document.category())));
            return view(store.find(documentId).orElseThrow());
        });
    }

    public DocumentView voidDocument(UUID personId, UUID documentId, String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("EXPENSE_VOID_REASON_REQUIRED", "撤销必须填写原因");
        }
        return transactions.inTenant(context -> {
            ExpenseDocument document = requireVisible(personId, true, documentId);
            if (!STATUS_POSTED.equals(document.status())) {
                throw ApiException.conflict("EXPENSE_STATE_INVALID",
                        "只有已入账单据可以撤销，当前状态：" + document.status());
            }
            store.markVoided(documentId, personId, text);
            store.releaseClaim(documentId, "voided");
            store.insertAudit(context.tenantId(), documentId, "voided", personId, "poster",
                    write(Map.of("reason", text, "previousStatus", STATUS_POSTED)));
            return view(store.find(documentId).orElseThrow());
        });
    }

    // ------------------------------------------------------------------ 批量异常暂停

    public List<ExpenseBatchHold> holds(String status, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIST));
        return transactions.inTenant(context -> store.listHolds(status, bounded));
    }

    public ExpenseBatchHold releaseHold(UUID personId, UUID holdId, String note) {
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("EXPENSE_HOLD_NOTE_REQUIRED", "解除批次暂停必须填写说明");
        }
        return transactions.inTenant(context -> {
            if (!store.releaseHold(context.tenantId(), holdId, personId, text)) {
                throw ApiException.conflict("EXPENSE_HOLD_NOT_ACTIVE",
                        "暂停记录不存在或已解除");
            }
            store.insertAudit(context.tenantId(), null, "batch_hold.released", personId,
                    "poster", write(Map.of("holdId", holdId.toString(), "note", text)));
            return store.findHold(holdId).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ 策略

    public ExpensePolicy policy() {
        return transactions.inTenant(context -> store.policy(context.tenantId()));
    }

    public ExpensePolicy updatePolicy(UUID actorPersonId, PolicyInput input) {
        validate(input);
        ExpensePolicy candidate = new ExpensePolicy(input.autoPostEnabled(),
                input.autoPostLimit(), input.windowDays(), input.legalTitle(), input.taxId(),
                input.vendorAllowlist(), input.manualCategories(), input.allowedCategories(),
                input.minConfidence(), input.verifyRequired(), 1);
        return transactions.inTenant(context -> {
            ExpensePolicy before = store.policy(context.tenantId());
            ExpensePolicy after = store.upsertPolicy(context.tenantId(), candidate);
            store.insertAudit(context.tenantId(), null, "policy.updated", actorPersonId, "system",
                    write(Map.of("before", before, "after", after)));
            return after;
        });
    }

    private static void validate(PolicyInput input) {
        if (input == null) {
            throw ApiException.badRequest("EXPENSE_POLICY_INVALID", "策略内容不能为空");
        }
        if (input.autoPostLimit() == null || input.autoPostLimit().signum() < 0) {
            throw ApiException.unprocessable("EXPENSE_POLICY_INVALID", "自动入账阈值不能为负数");
        }
        if (input.windowDays() < 1 || input.windowDays() > 3650) {
            throw ApiException.unprocessable("EXPENSE_POLICY_INVALID", "报销窗口必须在 1-3650 天之间");
        }
        if (input.minConfidence() == null || input.minConfidence().signum() < 0
                || input.minConfidence().compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("EXPENSE_POLICY_INVALID", "置信度门槛必须在 0-1 之间");
        }
    }

    private String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
