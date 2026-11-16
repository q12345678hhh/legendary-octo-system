package com.acme.platform.expense.app;

import java.math.BigDecimal;
import java.util.List;

/**
 * 租户预审策略：阈值、白名单与必须人工复核的科目。
 */
public record ExpensePolicy(
        boolean autoPostEnabled,
        BigDecimal autoPostLimit,
        int windowDays,
        String legalTitle,
        String taxId,
        List<String> vendorAllowlist,
        List<String> manualCategories,
        List<String> allowedCategories,
        BigDecimal minConfidence,
        boolean verifyRequired,
        int version) {

    public ExpensePolicy {
        autoPostLimit = autoPostLimit == null ? new BigDecimal("5000") : autoPostLimit;
        windowDays = windowDays <= 0 ? 90 : windowDays;
        legalTitle = legalTitle == null ? "" : legalTitle.strip();
        taxId = taxId == null ? "" : taxId.strip();
        vendorAllowlist = vendorAllowlist == null ? List.of() : List.copyOf(vendorAllowlist);
        manualCategories = manualCategories == null ? List.of() : List.copyOf(manualCategories);
        allowedCategories = allowedCategories == null ? List.of() : List.copyOf(allowedCategories);
        minConfidence = minConfidence == null ? new BigDecimal("0.70") : minConfidence;
        version = version <= 0 ? 1 : version;
    }

    public static ExpensePolicy defaults() {
        return new ExpensePolicy(true, new BigDecimal("5000"), 90, "", "", List.of(), List.of(),
                List.of(), new BigDecimal("0.70"), true, 1);
    }
}
