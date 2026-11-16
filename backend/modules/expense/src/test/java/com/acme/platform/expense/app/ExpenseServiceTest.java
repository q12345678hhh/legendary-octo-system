package com.acme.platform.expense.app;

import com.acme.platform.expense.ExpenseRuleEngine;
import com.acme.platform.expense.ExpenseStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.expense.ocr.DocumentExtractor;
import com.acme.platform.expense.ocr.ExtractedFields;
import com.acme.platform.expense.ocr.Extraction;
import com.acme.platform.expense.ocr.OcrProperties;
import com.acme.platform.expense.ocr.OcrStatus;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.file.FileRecord;
import com.acme.platform.platform.file.FileScanService;
import com.acme.platform.platform.file.FileService;
import com.acme.platform.platform.storage.ObjectStorageClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExpenseServiceTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000e1");
    private static final UUID UPLOADER = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1");
    private static final UUID REVIEWER = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a2");
    private static final UUID POSTER = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a3");
    private static final UUID FILE_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000f1");
    private static final String SUBJECT = "aaaaaaaa-0000-4000-8000-0000000000a1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> status = new AtomicReference<>("manual_review");
    private ExpenseStore store;
    private TenantTransactions transactions;
    private FileService files;
    private FileScanService scanner;
    private ObjectStorageClient storage;
    private DocumentExtractor extractor;
    private ExpenseService service;

    @BeforeEach
    void setUp() {
        store = mock(ExpenseStore.class);
        transactions = mock(TenantTransactions.class);
        files = mock(FileService.class);
        scanner = mock(FileScanService.class);
        storage = mock(ObjectStorageClient.class);
        extractor = mock(DocumentExtractor.class);

        RequestContext context = mock(RequestContext.class);
        when(context.tenantId()).thenReturn(TENANT);
        when(transactions.inTenant(any())).thenAnswer(invocation -> {
            Function<RequestContext, Object> work = invocation.getArgument(0);
            return work.apply(context);
        });
        when(files.find(FILE_ID)).thenReturn(file());
        when(extractor.configured()).thenReturn(true);
        when(extractor.extract(any(), anyString(), anyString())).thenReturn(ok());
        when(storage.download(anyString(), anyLong())).thenReturn(new byte[]{1, 2, 3});
        when(store.policy(TENANT)).thenReturn(policy());
        when(store.claimInvoice(any(), any(), any(), any())).thenReturn(true);
        when(store.sameFileSubmitted(any(), any())).thenReturn(false);
        when(store.batchCounts(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ExpenseStore.BatchCounts(0, 0, 0));
        when(store.lastReviewer(any())).thenReturn(REVIEWER);
        when(store.extraction(any())).thenReturn(Optional.empty());
        when(store.find(any())).thenAnswer(invocation -> Optional.of(document(status.get())));
        org.mockito.Mockito.doAnswer(invocation -> {
            status.set("posted");
            return null;
        }).when(store).markPosted(any(), any(), anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            status.set((String) invocation.getArgument(1));
            return null;
        }).when(store).markStatus(any(), anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            status.set("voided");
            return null;
        }).when(store).markVoided(any(), any(), anyString());

        OcrProperties properties = new OcrProperties("http://localhost", "key", "model",
                java.time.Duration.ofSeconds(5), 8L * 1024 * 1024, "wp04-03.test");
        service = new ExpenseService(store, new ExpenseRuleEngine(), transactions, files, scanner,
                storage, extractor, properties, objectMapper);
    }

    @Test
    void autoPostsWhenAllRulesPass() {
        ExpenseService.DocumentView view = service.submit(UPLOADER, SUBJECT,
                new ExpenseService.CreateInput(FILE_ID, "invoice", null));

        assertThat(view.document().status()).isEqualTo("posted");
        verify(store).markPosted(any(), isNull(), eq("ai"));
        verify(store).insertAudit(eq(TENANT), any(), eq("auto_posted"), isNull(), eq("ai"),
                anyString());
    }

    @Test
    void ocrUnavailableFallsToManualReview() {
        when(extractor.configured()).thenReturn(false);

        ExpenseService.DocumentView view = service.submit(UPLOADER, SUBJECT,
                new ExpenseService.CreateInput(FILE_ID, "invoice", null));

        assertThat(view.document().status()).isEqualTo("manual_review");
        verify(store, never()).markPosted(any(), any(), anyString());
    }

    @Test
    void duplicateInvoiceFallsToManualReview() {
        when(store.claimInvoice(any(), any(), any(), any())).thenReturn(false);

        ExpenseService.DocumentView view = service.submit(UPLOADER, SUBJECT,
                new ExpenseService.CreateInput(FILE_ID, "invoice", null));

        assertThat(view.document().status()).isEqualTo("manual_review");
        verify(store, never()).markPosted(any(), any(), anyString());
    }

    @Test
    void manualFieldsSkipOcrAndStillRunRules() {
        ExtractedFields fields = ok().fields();
        ExpenseService.DocumentView view = service.submit(UPLOADER, SUBJECT,
                new ExpenseService.CreateInput(FILE_ID, "invoice", fields));

        assertThat(view.document().status()).isEqualTo("posted");
        verify(extractor, never()).extract(any(), anyString(), anyString());
    }

    @Test
    void reviewerCannotReviewOwnDocument() {
        assertThatThrownBy(() -> service.review(UPLOADER, UUID.randomUUID(), "approve", "", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("EXPENSE_SOD_VIOLATION");
                });
    }

    @Test
    void approveThenPostAndVoidKeepsTrail() {
        service.review(REVIEWER, UUID.randomUUID(), "approve", "票据清晰", null);
        verify(store).markStatus(any(), eq("approved"));

        ExpenseService.DocumentView posted = service.post(POSTER, UUID.randomUUID());
        assertThat(posted.document().status()).isEqualTo("posted");
        verify(store).markPosted(any(), eq(POSTER), eq("employee"));

        ExpenseService.DocumentView voided = service.voidDocument(POSTER, UUID.randomUUID(),
                "发票作废");
        assertThat(voided.document().status()).isEqualTo("voided");
        verify(store).releaseClaim(any(), eq("voided"));
        verify(store).insertAudit(eq(TENANT), any(), eq("voided"), eq(POSTER), eq("poster"),
                anyString());
    }

    @Test
    void posterCannotBeUploader() {
        assertThatThrownBy(() -> service.post(UPLOADER, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("EXPENSE_SOD_VIOLATION");
                    assertThat(e.getMessage()).contains("提交人");
                });
        verify(store, never()).markPosted(any(), any(), anyString());
    }

    @Test
    void posterCannotBeLastReviewer() {
        assertThatThrownBy(() -> service.post(REVIEWER, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("EXPENSE_SOD_VIOLATION");
                    assertThat(e.getMessage()).contains("复核人");
                });
        verify(store, never()).markPosted(any(), any(), anyString());
    }

    @Test
    void batchAnomalyCreatesHoldAndBlocksAutoPost() {
        when(store.batchCounts(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ExpenseStore.BatchCounts(4, 0, 0));
        when(store.insertHoldIfAbsent(any(), eq("vendor"), anyString(), anyString(), anyInt(),
                anyInt(), any(), any())).thenReturn(true);
        when(store.activeHoldReason(eq(TENANT), eq("vendor"), anyString()))
                .thenReturn("24 小时内同一供应商提交 5 张单据，达到阈值 5 张");

        ExpenseService.DocumentView view = service.submit(UPLOADER, SUBJECT,
                new ExpenseService.CreateInput(FILE_ID, "invoice", null));

        assertThat(view.document().status()).isEqualTo("manual_review");
        verify(store, never()).markPosted(any(), any(), anyString());
        verify(store).insertHoldIfAbsent(eq(TENANT), eq("vendor"), anyString(), anyString(),
                anyInt(), eq(5), isNull(), eq(UPLOADER));
        verify(store).insertAudit(eq(TENANT), isNull(), eq("batch_anomaly.detected"),
                eq(UPLOADER), eq("ai"), anyString());
    }

    @Test
    void existingActiveHoldBlocksAutoPostEvenBelowThreshold() {
        when(store.activeHoldReason(eq(TENANT), eq("uploader"), anyString()))
                .thenReturn("24 小时内同一上传人提交 10 张单据");

        ExpenseService.DocumentView view = service.submit(UPLOADER, SUBJECT,
                new ExpenseService.CreateInput(FILE_ID, "invoice", null));

        assertThat(view.document().status()).isEqualTo("manual_review");
        verify(store, never()).markPosted(any(), any(), anyString());
    }

    @Test
    void releaseHoldRequiresNoteAndWritesAudit() {
        UUID holdId = UUID.randomUUID();
        assertThatThrownBy(() -> service.releaseHold(REVIEWER, holdId, "  "))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("EXPENSE_HOLD_NOTE_REQUIRED");
                });

        when(store.releaseHold(eq(TENANT), eq(holdId), eq(REVIEWER), anyString()))
                .thenReturn(true);
        when(store.findHold(holdId)).thenReturn(Optional.of(hold(holdId)));

        ExpenseBatchHold released = service.releaseHold(REVIEWER, holdId, "已核对为正常批量报销");

        assertThat(released.status()).isEqualTo("released");
        verify(store).insertAudit(eq(TENANT), isNull(), eq("batch_hold.released"),
                eq(REVIEWER), eq("poster"), anyString());
    }

    @Test
    void voidRequiresReason() {
        assertThatThrownBy(() -> service.voidDocument(REVIEWER, UUID.randomUUID(), "   "))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("EXPENSE_VOID_REASON_REQUIRED");
                });
    }

    @Test
    void rejectRequiresComment() {
        assertThatThrownBy(() -> service.review(REVIEWER, UUID.randomUUID(), "reject", " ", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("EXPENSE_REJECT_COMMENT_REQUIRED");
                });
    }

    private static FileRecord file() {
        return new FileRecord(FILE_ID, TENANT, SUBJECT, "invoice.png", "image/png", 1024,
                "abc123", "tenant-a/invoice.png", "available", "clean", null,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static Extraction ok() {
        ExtractedFields fields = new ExtractedFields(new BigDecimal("128.50"), "CNY", "044",
                "88000011", "云图科技", "91310000XYZ", LocalDate.now().minusDays(1), "差旅",
                "上海云图科技有限公司", List.of());
        return new Extraction(OcrStatus.OK, fields, Map.of("amount", 0.99, "currency", 0.99,
                "invoiceNo", 0.98, "occurredOn", 0.97, "vendorName", 0.96),
                "原文", "provider", "model", 12, null);
    }

    private static ExpensePolicy policy() {
        return new ExpensePolicy(true, new BigDecimal("5000"), 900, "", "", List.of(), List.of(),
                List.of(), new BigDecimal("0.70"), false, 3);
    }

    private static ExpenseBatchHold hold(UUID id) {
        return new ExpenseBatchHold(id, "vendor", "云图科技", "24 小时内同一供应商提交 5 张单据",
                24, 5, new BigDecimal("640.00"), "released", UPLOADER, OffsetDateTime.now(),
                REVIEWER, OffsetDateTime.now(), "已核对为正常批量报销");
    }

    private static ExpenseDocument document(String status) {
        return new ExpenseDocument(UUID.randomUUID(), UPLOADER, FILE_ID, "invoice", status,
                "CNY", new BigDecimal("128.50"), "044", "88000011", "云图科技", "91310000XYZ",
                LocalDate.now().minusDays(1), "差旅", List.of(), Map.of(),
                ExpenseRuleEngine.RULE_VERSION, 3, null, null, null, null, null, null, null,
                OffsetDateTime.now());
    }
}
