package com.acme.platform.expense.ocr;

import java.util.Map;

/**
 * 一次抽取的结果快照。
 *
 * @param status     抽取状态
 * @param fields     结构化字段
 * @param confidence 字段名到置信度（0-1）
 * @param ocrText    OCR 原文；仅落不可变表，不写日志
 * @param provider   供应商标识
 * @param model      模型标识
 * @param latencyMs  调用耗时
 * @param errorCode  失败原因码
 */
public record Extraction(
        OcrStatus status,
        ExtractedFields fields,
        Map<String, Double> confidence,
        String ocrText,
        String provider,
        String model,
        long latencyMs,
        String errorCode) {

    public Extraction {
        fields = fields == null ? ExtractedFields.empty() : fields;
        confidence = confidence == null ? Map.of() : Map.copyOf(confidence);
    }

    public static Extraction manual(ExtractedFields fields) {
        return new Extraction(OcrStatus.MANUAL, fields, Map.of(), null, "manual", "manual", 0, null);
    }

    public static Extraction failure(OcrStatus status, String errorCode, long latencyMs) {
        return new Extraction(status, ExtractedFields.empty(), Map.of(), null, null, null,
                latencyMs, errorCode);
    }

    public boolean usableForAutoReview() {
        return status == OcrStatus.OK || status == OcrStatus.MANUAL;
    }

    /** 规则引擎使用的字段置信度：人工填写视为 1.0，缺省字段按 0 处理。 */
    public double confidenceOf(String field) {
        if (status == OcrStatus.MANUAL) {
            return 1.0;
        }
        return confidence.getOrDefault(field, 0.0);
    }
}
