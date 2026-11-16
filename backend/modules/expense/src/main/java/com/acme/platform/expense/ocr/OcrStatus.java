package com.acme.platform.expense.ocr;

/**
 * 抽取状态：只有 ok 允许进入自动预审；其余状态一律转人工。
 */
public enum OcrStatus {
    OK,
    UNAVAILABLE,
    TIMEOUT,
    INVALID,
    PDF_UNSUPPORTED,
    MANUAL
}
