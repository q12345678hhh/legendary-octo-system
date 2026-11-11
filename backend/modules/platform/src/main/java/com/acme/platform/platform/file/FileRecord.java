package com.acme.platform.platform.file;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 文件元数据。字节永远不落库，只有对象键、哈希和扫描结论。
 */
public record FileRecord(
        UUID id,
        UUID tenantId,
        String subjectId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        String objectKey,
        String status,
        String scanStatus,
        String scanDetail,
        OffsetDateTime createdAt,
        OffsetDateTime uploadedAt,
        OffsetDateTime scannedAt) {

    public boolean clean() {
        return "clean".equals(scanStatus);
    }

    public boolean available() {
        return "available".equals(status);
    }
}
