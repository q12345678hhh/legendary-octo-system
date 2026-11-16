package com.acme.platform.expense;

import com.acme.platform.expense.app.ExpenseFinding;
import com.acme.platform.expense.app.ExpensePolicy;
import com.acme.platform.expense.ocr.ExtractedFields;
import com.acme.platform.expense.ocr.Extraction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性预审规则引擎：同样的抽取结果与策略必须得到同样的结论。
 *
 * <p>规则版本随结论一起落库，便于审计时还原口径；本类不做任何外部调用。
 */
public final class ExpenseRuleEngine {

    public static final String RULE_VERSION = "expense-rules-2026.11";

    /** 与发票号占用、文件哈希重复相关的查询结果。 */
    public record Duplicates(boolean invoiceClaimed, boolean sameFileSubmitted) {
    }

    /**
     * 批量异常信号。
     *
     * @param vendorHit         同一供应商 24 小时内提交数量达到阈值
     * @param uploaderHit       同一上传人 24 小时内提交数量达到阈值
     * @param amountHit         金额相近的单据数量达到阈值
     * @param activeHoldReason  该批次已存在的暂停原因，非空表示必须人工处置
     */
    public record BatchSignals(boolean vendorHit, boolean uploaderHit, boolean amountHit,
                               String activeHoldReason) {

        public static BatchSignals none() {
            return new BatchSignals(false, false, false, null);
        }

        public boolean anyHit() {
            return vendorHit || uploaderHit || amountHit;
        }

        public boolean hasHold() {
            return activeHoldReason != null && !activeHoldReason.isBlank();
        }
    }

    public record Decision(boolean autoPost, List<ExpenseFinding> findings,
                           String ruleVersion, int policyVersion) {
    }

    public Decision evaluate(Extraction extraction, ExpensePolicy policy, Duplicates duplicates,
                             BatchSignals batch, String docType, LocalDate today) {
        List<ExpenseFinding> findings = new ArrayList<>();
        findings.add(new ExpenseFinding("R000", extraction.usableForAutoReview()
                ? ExpenseFinding.PASS : ExpenseFinding.BLOCKED,
                extraction.usableForAutoReview() ? "抽取结果可用于自动预审"
                        : "OCR 不可用或输出非法，必须转人工",
                extraction.status().name(), "OK 或 MANUAL", null));

        ExtractedFields fields = extraction.fields();
        findings.add(required(docType, fields));
        findings.add(linesTotal(fields));
        findings.add(titleMatch(fields, policy));
        findings.add(taxIdMatch(fields, policy));
        findings.add(dateWindow(fields, policy, today));
        findings.add(invoiceDuplicate(docType, fields, duplicates));
        findings.add(fileDuplicate(duplicates));
        findings.add(vendorAllowlist(fields, policy));
        findings.add(amountLimit(fields, policy));
        findings.add(confidenceGate(extraction, fields, policy));
        findings.add(manualCategory(fields, policy));
        findings.add(invoiceVerification(docType, policy));
        findings.add(allowedCategory(fields, policy));
        findings.add(batchAnomaly(batch == null ? BatchSignals.none() : batch));

        boolean blocked = findings.stream().anyMatch(ExpenseFinding::blocking);
        return new Decision(!blocked && policy.autoPostEnabled(), List.copyOf(findings),
                RULE_VERSION, policy.version());
    }

    private static ExpenseFinding required(String docType, ExtractedFields fields) {
        List<String> missing = new ArrayList<>();
        if (fields.amount() == null || fields.amount().signum() <= 0) {
            missing.add("金额");
        }
        if (blank(fields.currency())) {
            missing.add("币种");
        }
        if (fields.occurredOn() == null) {
            missing.add("发生日期");
        }
        if ("invoice".equals(docType) && blank(fields.invoiceNo())) {
            missing.add("发票号");
        }
        if (blank(fields.vendorName()) && blank(fields.vendorTaxNo())) {
            missing.add("供应商或税号");
        }
        return missing.isEmpty()
                ? ExpenseFinding.pass("R001", "必填字段齐全")
                : ExpenseFinding.fail("R001", "缺少必填字段：" + String.join("、", missing),
                        String.join("、", missing), "全部必填字段");
    }

    private static ExpenseFinding linesTotal(ExtractedFields fields) {
        if (fields.lines().isEmpty()) {
            return ExpenseFinding.skip("R002", "未提供明细，跳过合计校验");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ExtractedFields.LineItem line : fields.lines()) {
            if (line.amount() == null) {
                return ExpenseFinding.skip("R002", "明细金额不完整，跳过合计校验");
            }
            sum = sum.add(line.amount());
        }
        BigDecimal total = fields.amount();
        if (total == null) {
            return ExpenseFinding.fail("R002", "缺少票面金额，无法校验明细合计",
                    null, sum.toPlainString());
        }
        boolean match = total.subtract(sum).abs().compareTo(new BigDecimal("0.01")) <= 0;
        return match
                ? ExpenseFinding.pass("R002", "明细合计与票面一致")
                : ExpenseFinding.fail("R002", "明细合计与票面金额不一致",
                        sum.toPlainString(), total.toPlainString());
    }

    private static ExpenseFinding titleMatch(ExtractedFields fields, ExpensePolicy policy) {
        if (blank(policy.legalTitle())) {
            return ExpenseFinding.skip("R003", "未配置法定抬头，跳过抬头校验");
        }
        String title = fields.title();
        boolean match = title != null && title.strip().equalsIgnoreCase(policy.legalTitle().strip());
        return match
                ? ExpenseFinding.pass("R003", "抬头与租户配置一致")
                : ExpenseFinding.fail("R003", "抬头与租户配置不一致",
                        title == null ? "未识别" : title, policy.legalTitle());
    }

    private static ExpenseFinding taxIdMatch(ExtractedFields fields, ExpensePolicy policy) {
        if (blank(policy.taxId())) {
            return ExpenseFinding.skip("R004", "未配置税号，跳过税号校验");
        }
        boolean match = policy.taxId().equalsIgnoreCase(fields.vendorTaxNo() == null
                ? "" : fields.vendorTaxNo().strip());
        return match
                ? ExpenseFinding.pass("R004", "税号与租户配置一致")
                : ExpenseFinding.fail("R004", "税号与租户配置不一致",
                        fields.vendorTaxNo() == null ? "未识别" : fields.vendorTaxNo(),
                        policy.taxId());
    }

    private static ExpenseFinding dateWindow(ExtractedFields fields, ExpensePolicy policy,
                                             LocalDate today) {
        LocalDate occurred = fields.occurredOn();
        if (occurred == null) {
            return ExpenseFinding.fail("R005", "缺少发生日期", null, today.toString());
        }
        if (occurred.isAfter(today)) {
            return ExpenseFinding.fail("R005", "发生日期不能晚于今天",
                    occurred.toString(), today.toString());
        }
        LocalDate earliest = today.minusDays(policy.windowDays());
        if (occurred.isBefore(earliest)) {
            return ExpenseFinding.fail("R005", "发生日期超出报销窗口",
                    occurred.toString(), earliest + " 之后");
        }
        return ExpenseFinding.pass("R005", "发生日期在报销窗口内");
    }

    private static ExpenseFinding invoiceDuplicate(String docType, ExtractedFields fields,
                                                   Duplicates duplicates) {
        if (!"invoice".equals(docType) || blank(fields.invoiceNo())) {
            return ExpenseFinding.skip("R006", "非发票或未识别发票号，跳过发票号去重");
        }
        return duplicates.invoiceClaimed()
                ? ExpenseFinding.fail("R006", "该发票号已被其他单据占用",
                        fields.invoiceNo(), "未被占用")
                : ExpenseFinding.pass("R006", "发票号未被占用");
    }

    private static ExpenseFinding fileDuplicate(Duplicates duplicates) {
        return duplicates.sameFileSubmitted()
                ? ExpenseFinding.fail("R007", "同一文件已提交过有效单据", "重复文件", "新文件")
                : ExpenseFinding.pass("R007", "未发现重复文件");
    }

    private static ExpenseFinding vendorAllowlist(ExtractedFields fields, ExpensePolicy policy) {
        if (policy.vendorAllowlist().isEmpty()) {
            return ExpenseFinding.skip("R008", "未配置供应商白名单，跳过校验");
        }
        String name = fields.vendorName() == null ? "" : fields.vendorName().strip();
        String taxNo = fields.vendorTaxNo() == null ? "" : fields.vendorTaxNo().strip();
        boolean match = policy.vendorAllowlist().stream().anyMatch(value ->
                value.equalsIgnoreCase(name) || value.equalsIgnoreCase(taxNo));
        return match
                ? ExpenseFinding.pass("R008", "供应商在白名单内")
                : ExpenseFinding.fail("R008", "供应商不在白名单内",
                        blank(name) ? taxNo : name, String.join("、", policy.vendorAllowlist()));
    }

    private static ExpenseFinding amountLimit(ExtractedFields fields, ExpensePolicy policy) {
        if (fields.amount() == null) {
            return ExpenseFinding.blocked("R009", "缺少金额，无法判断自动入账阈值");
        }
        boolean within = fields.amount().compareTo(policy.autoPostLimit()) <= 0;
        return within
                ? ExpenseFinding.pass("R009", "金额在自动入账阈值内")
                : ExpenseFinding.blocked("R009", "金额超过自动入账阈值，需要人工复核");
    }

    private static ExpenseFinding confidenceGate(Extraction extraction, ExtractedFields fields,
                                                 ExpensePolicy policy) {
        String[] keys = {"amount", "currency", "invoiceNo", "occurredOn", "vendorName"};
        double minimum = Double.MAX_VALUE;
        for (String key : keys) {
            minimum = Math.min(minimum, extraction.confidenceOf(key));
        }
        if (minimum == Double.MAX_VALUE) {
            return ExpenseFinding.skip("R010", "无字段置信度，跳过置信度门槛");
        }
        BigDecimal required = policy.minConfidence();
        return BigDecimal.valueOf(minimum).compareTo(required) >= 0
                ? ExpenseFinding.pass("R010", "关键字段置信度达到门槛")
                : ExpenseFinding.blocked("R010", "关键字段置信度不足，需要人工确认");
    }

    private static ExpenseFinding manualCategory(ExtractedFields fields, ExpensePolicy policy) {
        if (policy.manualCategories().isEmpty()) {
            return ExpenseFinding.skip("R011", "未配置必须人工复核的科目");
        }
        String category = fields.category() == null ? "" : fields.category().strip();
        boolean manual = policy.manualCategories().stream()
                .anyMatch(value -> value.equalsIgnoreCase(category));
        return manual
                ? ExpenseFinding.blocked("R011", "该科目必须人工复核：" + category)
                : ExpenseFinding.pass("R011", "科目不需要强制人工复核");
    }

    private static ExpenseFinding invoiceVerification(String docType, ExpensePolicy policy) {
        if (!"invoice".equals(docType)) {
            return ExpenseFinding.skip("R012", "非发票，跳过验真");
        }
        return policy.verifyRequired()
                ? ExpenseFinding.blocked("R012", "发票验真服务未接入，按策略必须人工复核")
                : ExpenseFinding.pass("R012", "租户已关闭强制验真");
    }

    private static ExpenseFinding allowedCategory(ExtractedFields fields, ExpensePolicy policy) {
        if (policy.allowedCategories().isEmpty()) {
            return ExpenseFinding.skip("R013", "未配置允许科目，跳过科目校验");
        }
        String category = fields.category() == null ? "" : fields.category().strip();
        boolean allowed = policy.allowedCategories().stream()
                .anyMatch(value -> value.equalsIgnoreCase(category));
        return allowed
                ? ExpenseFinding.pass("R013", "科目在允许范围内")
                : ExpenseFinding.fail("R013", "科目不在允许范围内",
                        blank(category) ? "未识别" : category,
                        String.join("、", policy.allowedCategories()));
    }

    private static ExpenseFinding batchAnomaly(BatchSignals batch) {
        if (batch.hasHold()) {
            return ExpenseFinding.blocked("R014", "该批次已暂停自动入账：" + batch.activeHoldReason());
        }
        if (batch.anyHit()) {
            List<String> hits = new ArrayList<>();
            if (batch.vendorHit()) {
                hits.add("同一供应商");
            }
            if (batch.uploaderHit()) {
                hits.add("同一上传人");
            }
            if (batch.amountHit()) {
                hits.add("金额集中");
            }
            return ExpenseFinding.blocked("R014",
                    "检测到批量异常（" + String.join("、", hits) + "），转人工复核");
        }
        return ExpenseFinding.pass("R014", "未发现批量异常");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
