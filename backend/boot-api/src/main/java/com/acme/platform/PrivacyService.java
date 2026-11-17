package com.acme.platform;

import com.acme.platform.expense.ExpenseStore;
import com.acme.platform.expense.app.ExpenseDocument;
import com.acme.platform.expense.ocr.OcrProperties;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.retention.TombstoneStore;
import com.acme.platform.tenancy.privacy.PrivacySetting;
import com.acme.platform.tenancy.privacy.PrivacySettingsService;
import com.acme.platform.tenancy.retention.RetentionEventStore;
import com.acme.platform.tenancy.retention.RetentionPolicy;
import com.acme.platform.tenancy.retention.RetentionService;
import com.acme.platform.worklog.report.summary.ReportAiProperties;
import com.acme.platform.worklog.voice.AsrProperties;
import com.acme.platform.worklog.voice.VoiceCapture;
import com.acme.platform.worklog.voice.VoiceCaptureStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 隐私中心：数据分类说明、租户隐私设置、我的数据导出与删除。
 *
 * <p>删除遵循统一回收站语义：语音与未入账单据进入回收站；已入账单据属于会计档案，
 * 只匿名化 OCR 原文并写墓碑与审计，金额、状态与审计链保持不变。
 */
@Service
public class PrivacyService {

    private static final int LIST_LIMIT = 200;

    private final PrivacySettingsService settings;
    private final VoiceCaptureStore voiceCaptures;
    private final ExpenseStore expenses;
    private final RetentionService retention;
    private final RetentionPolicy retentionPolicy;
    private final RetentionEventStore retentionEvents;
    private final TombstoneStore tombstones;
    private final AsrProperties asr;
    private final OcrProperties ocr;
    private final ReportAiProperties reportAi;
    private final TenantTransactions transactions;
    private final ObjectMapper objectMapper;

    PrivacyService(PrivacySettingsService settings, VoiceCaptureStore voiceCaptures,
                   ExpenseStore expenses, RetentionService retention,
                   RetentionPolicy retentionPolicy, RetentionEventStore retentionEvents,
                   TombstoneStore tombstones, AsrProperties asr, OcrProperties ocr,
                   ReportAiProperties reportAi, TenantTransactions transactions,
                   ObjectMapper objectMapper) {
        this.settings = settings;
        this.voiceCaptures = voiceCaptures;
        this.expenses = expenses;
        this.retention = retention;
        this.retentionPolicy = retentionPolicy;
        this.retentionEvents = retentionEvents;
        this.tombstones = tombstones;
        this.asr = asr;
        this.ocr = ocr;
        this.reportAi = reportAi;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    record DataClassView(String dataClass, String purpose, int retentionDays,
                         String retentionNote, List<String> accessRoles) {
    }

    record SupplierView(String surface, String provider, String model, boolean configured) {
    }

    record SettingsView(String dataResidency, boolean trainingOptOut,
                        int voiceTranscriptRetentionDays, int expenseOcrRetentionDays,
                        String updatedBy, OffsetDateTime updatedAt,
                        List<SupplierView> suppliers) {
    }

    record VoiceDataView(UUID id, LocalDate workDate, String status, String provider,
                         String model, OffsetDateTime audioDeletedAt,
                         OffsetDateTime createdAt, boolean transcribed) {
    }

    record ExpenseDataView(UUID id, String docType, String status, BigDecimal amount,
                           String currency, OffsetDateTime createdAt) {
    }

    record MyDataView(UUID personId, List<VoiceDataView> voiceCaptures,
                      List<ExpenseDataView> expenseDocuments) {
    }

    record VoiceExportView(UUID id, LocalDate workDate, String status, String provider,
                           String model, String transcriptText, String editedText,
                           OffsetDateTime audioDeletedAt, OffsetDateTime createdAt) {
    }

    record ExpenseExportView(UUID id, String docType, String status, BigDecimal amount,
                             String currency, String vendorName, String invoiceCode,
                             String invoiceNo, LocalDate occurredOn, OffsetDateTime createdAt,
                             ExpenseDocument.ExtractionSummary extraction) {
    }

    record ExportView(UUID personId, OffsetDateTime generatedAt,
                      List<VoiceExportView> voiceCaptures,
                      List<ExpenseExportView> expenseDocuments) {
    }

    record DeleteAction(UUID id, String dataClass, String action) {
    }

    record DeleteResult(List<DeleteAction> actions) {
    }

    List<DataClassView> dataClasses() {
        PrivacySetting setting = settings.current();
        return List.of(
                new DataClassView("file", "业务附件与单据原文件", retentionPolicy.recycleBinDays(),
                        "删除后进入回收站，到期物理清理", List.of("file.read")),
                new DataClassView("worklog_entry", "手工与语音确认后的工时",
                        3650, "工时作为绩效与合规依据长期保留", List.of("worklog.time.read")),
                new DataClassView("worklog_weekly_report", "周报正文与提交版本",
                        3650, "周报随审批链长期保留", List.of("worklog.report.read")),
                new DataClassView("worklog_voice_capture",
                        "语音转写文本；音频在确认后立即删除",
                        setting.voiceTranscriptRetentionDays(),
                        "转写保留天数可由租户调整", List.of("worklog.time.read", "本人")),
                new DataClassView("expense_document", "报销单/发票/账单结构化字段",
                        3650, "已入账单据不可删除，只能匿名化", List.of(
                                "expense.document.read", "expense.document.review", "本人")),
                new DataClassView("expense_extraction", "OCR 原文与抽取字段",
                        setting.expenseOcrRetentionDays(),
                        "OCR 保留天数可由租户调整，已入账只匿名化", List.of(
                                "expense.document.read", "expense.document.review")),
                new DataClassView("audit", "授权、单据与保留审计记录",
                        retentionPolicy.auditDays(), "审计只读，按合规下限保留",
                        List.of("authorization.audit.read")));
    }

    SettingsView settings() {
        return toView(settings.current());
    }

    SettingsView updateSettings(PrivacySettingsService.Input input) {
        return toView(settings.update(input));
    }

    MyDataView myData(UUID personId) {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context, ctx -> new MyDataView(personId,
                voiceCaptures.listByPerson(personId, LIST_LIMIT).stream()
                        .map(PrivacyService::toDataView).toList(),
                expenses.list(personId, null, LIST_LIMIT).stream()
                        .map(PrivacyService::toDataView).toList()));
    }

    ExportView export(UUID personId) {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context, ctx -> new ExportView(personId,
                OffsetDateTime.now(),
                voiceCaptures.listByPerson(personId, LIST_LIMIT).stream()
                        .map(capture -> new VoiceExportView(capture.id(), capture.workDate(),
                                capture.status(), capture.provider(), capture.model(),
                                capture.transcriptText(), capture.editedText(),
                                capture.audioDeletedAt(), capture.createdAt()))
                        .toList(),
                expenses.list(personId, null, LIST_LIMIT).stream()
                        .map(doc -> new ExpenseExportView(doc.id(), doc.docType(), doc.status(),
                                doc.amount(), doc.currency(), doc.vendorName(),
                                doc.invoiceCode(), doc.invoiceNo(), doc.occurredOn(),
                                doc.createdAt(), expenses.extraction(doc.id()).orElse(null)))
                        .toList()));
    }

    DeleteResult delete(UUID personId, List<UUID> voiceCaptureIds,
                        List<UUID> expenseDocumentIds) {
        RequestContext context = RequestContextHolder.current();
        return transactions.inTenant(context, ctx -> {
            List<DeleteAction> actions = new ArrayList<>();
            for (UUID id : voiceCaptureIds == null ? List.<UUID>of() : voiceCaptureIds) {
                VoiceCapture capture = voiceCaptures.findIncludingRecycled(id)
                        .orElseThrow(() -> ApiException.notFound("VOICE_CAPTURE_NOT_FOUND",
                                "录音不存在：" + id));
                if (!capture.personId().equals(personId)) {
                    throw ApiException.forbidden("VOICE_NOT_OWNER", "只能删除自己的录音");
                }
                retention.recycle("worklog_voice_capture", id);
                actions.add(new DeleteAction(id, "worklog_voice_capture", "recycled"));
            }
            for (UUID id : expenseDocumentIds == null ? List.<UUID>of() : expenseDocumentIds) {
                ExpenseDocument doc = expenses.findIncludingRecycled(id)
                        .orElseThrow(() -> ApiException.notFound("EXPENSE_DOCUMENT_NOT_FOUND",
                                "单据不存在：" + id));
                if (!doc.uploaderPersonId().equals(personId)) {
                    throw ApiException.forbidden("EXPENSE_NOT_OWNER", "只能删除本人提交的单据");
                }
                if ("posted".equals(doc.status())) {
                    anonymize(ctx, personId, doc);
                    actions.add(new DeleteAction(id, "expense_document", "anonymized"));
                } else {
                    retention.recycle("expense_document", id);
                    actions.add(new DeleteAction(id, "expense_document", "recycled"));
                }
            }
            return new DeleteResult(actions);
        });
    }

    private void anonymize(RequestContext context, UUID personId, ExpenseDocument doc) {
        expenses.anonymizeExtraction(doc.id());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", "data_subject_request");
        detail.put("personId", personId.toString());
        tombstones.record(context.tenantId(), "expense_document", doc.id(),
                subjectHash(personId), "anonymized", null, detail);
        retentionEvents.record(context.tenantId(), "anonymized", "expense_document",
                doc.id(), context.subjectId(), detail);
        expenses.insertAudit(context.tenantId(), doc.id(), "anonymized", personId, "employee",
                write(detail));
    }

    private SettingsView toView(PrivacySetting setting) {
        return new SettingsView(setting.dataResidency(), setting.trainingOptOut(),
                setting.voiceTranscriptRetentionDays(), setting.expenseOcrRetentionDays(),
                setting.updatedBy(), setting.updatedAt(), List.of(
                        supplier("语音转写", asr.model(), asr.configured()),
                        supplier("单据 OCR", ocr.model(), ocr.configured()),
                        supplier("AI 周报", reportAi.model(), reportAi.configured())));
    }

    private static SupplierView supplier(String surface, String model, boolean configured) {
        return new SupplierView(surface, configured ? "openai-compatible" : "not_configured",
                model, configured);
    }

    private static VoiceDataView toDataView(VoiceCapture capture) {
        return new VoiceDataView(capture.id(), capture.workDate(), capture.status(),
                capture.provider(), capture.model(), capture.audioDeletedAt(),
                capture.createdAt(), capture.transcriptText() != null);
    }

    private static ExpenseDataView toDataView(ExpenseDocument doc) {
        return new ExpenseDataView(doc.id(), doc.docType(), doc.status(), doc.amount(),
                doc.currency(), doc.createdAt());
    }

    private String write(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化隐私操作详情", e);
        }
    }

    private static String subjectHash(UUID personId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    personId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算主体哈希", e);
        }
    }
}
