package com.acme.platform.expense.ocr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 从票据中抽取出的结构化字段。
 *
 * <p>OCR 与人工填写共用本结构；人工填写时每个字段置信度按 1.0 处理，
 * 但仍然必须通过全部确定性规则。
 */
public record ExtractedFields(
        BigDecimal amount,
        String currency,
        String invoiceCode,
        String invoiceNo,
        String vendorName,
        String vendorTaxNo,
        LocalDate occurredOn,
        String category,
        String title,
        List<LineItem> lines) {

    public record LineItem(String description, BigDecimal amount) {
    }

    public ExtractedFields {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static ExtractedFields empty() {
        return new ExtractedFields(null, null, null, null, null, null, null, null, null, List.of());
    }
}
