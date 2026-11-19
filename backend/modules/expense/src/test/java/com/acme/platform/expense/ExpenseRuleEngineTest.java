package com.acme.platform.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.platform.expense.app.ExpenseFinding;
import com.acme.platform.expense.app.ExpensePolicy;
import com.acme.platform.expense.ocr.ExtractedFields;
import com.acme.platform.expense.ocr.Extraction;
import com.acme.platform.expense.ocr.OcrStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpenseRuleEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private final ExpenseRuleEngine engine = new ExpenseRuleEngine();

    @Test
    void autoPostsWhenEveryRulePasses() {
        ExpensePolicy policy = policy(true, false, "上海云图科技有限公司", "91310000XYZ",
                List.of("云图科技"), List.of(), List.of("差旅"),
                new BigDecimal("5000"), new BigDecimal("0.70"));

        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95), policy,
                new ExpenseRuleEngine.Duplicates(false, false), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isTrue();
        assertThat(decision.findings()).noneMatch(ExpenseFinding::blocking);
        assertThat(decision.ruleVersion()).isEqualTo(ExpenseRuleEngine.RULE_VERSION);
        assertThat(decision.policyVersion()).isEqualTo(3);
    }

    @Test
    void verifyRequiredBlocksAutoPostByDefault() {
        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95),
                ExpensePolicy.defaults(), new ExpenseRuleEngine.Duplicates(false, false),
                ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("R012");
            assertThat(finding.status()).isEqualTo(ExpenseFinding.BLOCKED);
        });
    }

    @Test
    void duplicateInvoiceAndFileForceManualReview() {
        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95),
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("5000"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(true, true), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).filteredOn(finding ->
                        List.of("R006", "R007").contains(finding.code()))
                .extracting(ExpenseFinding::status)
                .containsOnly(ExpenseFinding.FAIL);
    }

    @Test
    void amountAboveLimitIsBlocked() {
        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95),
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("100"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(false, false), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("R009");
            assertThat(finding.status()).isEqualTo(ExpenseFinding.BLOCKED);
        });
    }

    @Test
    void expiredDateFailsWindowRule() {
        ExtractedFields fields = new ExtractedFields(new BigDecimal("128.50"), "CNY", "044",
                "88000011", "云图科技", "91310000XYZ", TODAY.minusDays(120), "差旅",
                "上海云图科技有限公司", List.of());
        Extraction extraction = new Extraction(OcrStatus.OK, fields, confidence(0.95),
                "原文", "provider", "model", 10, null);

        ExpenseRuleEngine.Decision decision = engine.evaluate(extraction,
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("5000"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(false, false), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("R005");
            assertThat(finding.status()).isEqualTo(ExpenseFinding.FAIL);
        });
    }

    @Test
    void titleTaxAndVendorMismatchFailDeterministically() {
        ExpensePolicy policy = policy(true, false, "另一家公司", "OTHER-TAX",
                List.of("别的供应商"), List.of(), List.of("差旅"),
                new BigDecimal("5000"), new BigDecimal("0.70"));

        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95), policy,
                new ExpenseRuleEngine.Duplicates(false, false), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).filteredOn(finding ->
                        List.of("R003", "R004", "R008").contains(finding.code()))
                .extracting(ExpenseFinding::status)
                .containsOnly(ExpenseFinding.FAIL);
    }

    @Test
    void lowConfidenceAndManualCategoryBlock() {
        ExpensePolicy policy = policy(true, false, "", "", List.of(), List.of("差旅"),
                List.of("差旅"), new BigDecimal("5000"), new BigDecimal("0.80"));
        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.55), policy,
                new ExpenseRuleEngine.Duplicates(false, false), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).filteredOn(finding ->
                        List.of("R010", "R011").contains(finding.code()))
                .extracting(ExpenseFinding::status)
                .containsOnly(ExpenseFinding.BLOCKED);
    }

    @Test
    void lineSumMismatchFails() {
        ExtractedFields fields = new ExtractedFields(new BigDecimal("128.50"), "CNY", "044",
                "88000011", "云图科技", "91310000XYZ", TODAY.minusDays(1), "差旅",
                "上海云图科技有限公司",
                List.of(new ExtractedFields.LineItem("高铁", new BigDecimal("100.00"))));
        Extraction extraction = new Extraction(OcrStatus.OK, fields, confidence(0.95),
                "原文", "provider", "model", 10, null);

        ExpenseRuleEngine.Decision decision = engine.evaluate(extraction,
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("5000"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(false, false), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("R002");
            assertThat(finding.status()).isEqualTo(ExpenseFinding.FAIL);
        });
        assertThat(decision.autoPost()).isFalse();
    }

    @Test
    void unavailableOcrAlwaysBlocksEvenWithGoodFields() {
        Extraction extraction = new Extraction(OcrStatus.UNAVAILABLE, ok(0.95).fields(),
                confidence(0.99), null, null, null, 0, "OCR_NOT_CONFIGURED");

        ExpenseRuleEngine.Decision decision = engine.evaluate(extraction,
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("5000"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(false, false), ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("R000");
            assertThat(finding.status()).isEqualTo(ExpenseFinding.BLOCKED);
        });
    }

    @Test
    void activeBatchHoldBlocksAutoPostEvenWhenAllRulesPass() {
        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95),
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("5000"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(false, false),
                new ExpenseRuleEngine.BatchSignals(false, false, false,
                        "24 小时内同一供应商提交 5 张单据"), "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("R014");
            assertThat(finding.status()).isEqualTo(ExpenseFinding.BLOCKED);
        });
    }

    @Test
    void batchHitBlocksAutoPost() {
        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95),
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("5000"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(false, false),
                new ExpenseRuleEngine.BatchSignals(true, false, false, null),
                "invoice", TODAY);

        assertThat(decision.autoPost()).isFalse();
        assertThat(decision.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("R014");
            assertThat(finding.status()).isEqualTo(ExpenseFinding.BLOCKED);
            assertThat(finding.message()).contains("批量异常");
        });
    }

    @Test
    void noBatchSignalKeepsRulePassing() {
        ExpenseRuleEngine.Decision decision = engine.evaluate(ok(0.95),
                policy(true, false, "", "", List.of(), List.of(), List.of(),
                        new BigDecimal("5000"), new BigDecimal("0.70")),
                new ExpenseRuleEngine.Duplicates(false, false),
                ExpenseRuleEngine.BatchSignals.none(), "invoice", TODAY);

        assertThat(decision.autoPost()).isTrue();
        assertThat(decision.findings()).filteredOn(finding -> "R014".equals(finding.code()))
                .extracting(ExpenseFinding::status)
                .containsOnly(ExpenseFinding.PASS);
    }

    private static Extraction ok(double confidence) {
        ExtractedFields fields = new ExtractedFields(new BigDecimal("128.50"), "CNY", "044",
                "88000011", "云图科技", "91310000XYZ", TODAY.minusDays(1), "差旅",
                "上海云图科技有限公司", List.of());
        return new Extraction(OcrStatus.OK, fields, confidence(confidence), "原文",
                "provider", "model", 10, null);
    }

    private static Map<String, Double> confidence(double value) {
        return Map.of("amount", value, "currency", value, "invoiceNo", value,
                "occurredOn", value, "vendorName", value);
    }

    private static ExpensePolicy policy(boolean autoPost, boolean verify, String title, String taxId,
                                        List<String> vendors, List<String> manual,
                                        List<String> allowed, BigDecimal limit,
                                        BigDecimal minConfidence) {
        return new ExpensePolicy(autoPost, limit, 90, title, taxId, vendors, manual, allowed,
                minConfidence, verify, 3);
    }
}
