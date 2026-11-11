package com.acme.platform.platform.file;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 文件元数据存储。调用方必须先绑定租户上下文。
 */
@Component
public class FileStore {

    static final String COLUMNS = """
            id, tenant_id, subject_id, file_name, content_type, size_bytes, sha256, object_key,
            status, scan_status, scan_detail, created_at, uploaded_at, scanned_at
            """;

    static final RowMapper<FileRecord> MAPPER = (rs, rowNum) -> new FileRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("subject_id"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("sha256"),
            rs.getString("object_key"),
            rs.getString("status"),
            rs.getString("scan_status"),
            rs.getString("scan_detail"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("uploaded_at", java.time.OffsetDateTime.class),
            rs.getObject("scanned_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public FileStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public FileRecord insert(UUID id, UUID tenantId, String subjectId, String fileName,
                             String contentType, long sizeBytes, String sha256, String objectKey) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO platform_file
                    (id, tenant_id, subject_id, file_name, content_type, size_bytes, sha256, object_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING %s
                """.formatted(COLUMNS),
                MAPPER, id, tenantId, subjectId, fileName, contentType, sizeBytes, sha256, objectKey);
    }

    public Optional<FileRecord> find(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM platform_file WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public List<FileRecord> list(int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM platform_file ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    /** 只有第一次从 pending_upload 迁移时才返回 true，避免重复发布上传完成事件。 */
    public boolean markUploaded(UUID id) {
        return jdbcTemplate.update("""
                UPDATE platform_file
                   SET status = 'uploaded', uploaded_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'pending_upload'
                """, id) == 1;
    }

    public void markScanning(UUID id) {
        jdbcTemplate.update("""
                UPDATE platform_file SET scan_status = 'scanning', updated_at = now()
                 WHERE id = ? AND scan_status = 'pending'
                """, id);
    }

    public void markScanResult(UUID id, String scanStatus, String detail, String status) {
        jdbcTemplate.update("""
                UPDATE platform_file
                   SET scan_status = ?, scan_detail = ?, status = ?,
                       scanned_at = now(), updated_at = now()
                 WHERE id = ?
                """, scanStatus, detail, status, id);
    }
}
