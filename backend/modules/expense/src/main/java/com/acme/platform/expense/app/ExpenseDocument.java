package com.acme.platform.expense.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单据的完整持久化视图（不含 OCR 原文，原文单独存不可变表）。
 */
public record ExpenseDocument(
        UUID id,
        UUID uploaderPersonId,
        UUID fileId,
        String docType,
        String status,
        String currency,
        BigDecimal amount,
        String invoiceCode,
        String invoiceNo,
        String vendorName,
        String vendorTaxNo,
        LocalDate occurredOn,
        String category,
        List<ExpenseFinding> findings,
        Map<String, Double> confidence,
        String ruleVersion,
        int policyVersion,
        UUID extractionId,
        OffsetDateTime postedAt,
        UUID postedBy,
        String postActorKind,
        OffsetDateTime voidedAt,
        UUID voidedBy,
        String voidReason,
        OffsetDateTime createdAt) {

    public record ExtractionSummary(
            UUID id,
            String status,
            String provider,
            String model,
            String errorCode,
            String ocrText,
            OffsetDateTime createdAt) {
    }
}
