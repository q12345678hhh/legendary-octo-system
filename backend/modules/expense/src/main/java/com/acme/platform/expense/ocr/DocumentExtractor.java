package com.acme.platform.expense.ocr;

/**
 * 票据抽取端口：实现可以是视觉模型、专用 OCR 或人工填写。
 *
 * <p>实现不得抛业务异常：未配置、超时、上游失败与非法输出都通过
 * {@link Extraction#status()} 返回，由上层统一转人工。
 */
public interface DocumentExtractor {

    boolean configured();

    Extraction extract(byte[] content, String contentType, String fileName);
}
