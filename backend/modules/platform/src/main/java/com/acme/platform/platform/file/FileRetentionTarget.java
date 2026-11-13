package com.acme.platform.platform.file;

import com.acme.platform.platform.storage.ObjectStorageClient;
import com.acme.platform.platform.storage.ObjectStorageProperties;
import com.acme.platform.platform.retention.RetentionTarget;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 文件接入回收站：回收只改状态，清理时才删对象与元数据。
 */
@Component
public class FileRetentionTarget implements RetentionTarget {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorageClient storage;
    private final ObjectStorageProperties properties;

    public FileRetentionTarget(JdbcTemplate jdbcTemplate, ObjectStorageClient storage,
                               ObjectStorageProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String dataClass() {
        return "file";
    }

    @Override
    public Optional<Map<String, Object>> snapshot(UUID objectId) {
        return jdbcTemplate.query("""
                        SELECT file_name, content_type, size_bytes, sha256, object_key, status, created_at
                        FROM platform_file WHERE id = ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("objectRef", rs.getString("object_key"));
                    snapshot.put("fileName", rs.getString("file_name"));
                    snapshot.put("contentType", rs.getString("content_type"));
                    snapshot.put("sizeBytes", rs.getLong("size_bytes"));
                    snapshot.put("sha256", rs.getString("sha256"));
                    snapshot.put("status", rs.getString("status"));
                    snapshot.put("createdAt", String.valueOf(rs.getObject("created_at")));
                    return snapshot;
                }, objectId)
                .stream().findFirst();
    }

    @Override
    public void markRecycled(UUID objectId) {
        jdbcTemplate.update("""
                UPDATE platform_file SET status = 'recycled', updated_at = now()
                WHERE id = ? AND status <> 'recycled'
                """, objectId);
    }

    @Override
    public void restore(UUID objectId) {
        jdbcTemplate.update("""
                UPDATE platform_file SET status = 'available', updated_at = now()
                WHERE id = ? AND status = 'recycled'
                """, objectId);
    }

    @Override
    public void purge(UUID objectId) {
        String objectKey = jdbcTemplate.query(
                        "SELECT object_key FROM platform_file WHERE id = ?",
                        (rs, rowNum) -> rs.getString("object_key"), objectId)
                .stream().findFirst().orElse(null);
        if (objectKey == null) {
            return;
        }
        deleteObject(objectKey);
        jdbcTemplate.update("DELETE FROM platform_file WHERE id = ?", objectId);
    }

    /** 生产必须配置对象存储凭据；本地/测试没有凭据时只清元数据。 */
    private void deleteObject(String objectKey) {
        boolean configured = properties.accessKey() != null && !properties.accessKey().isBlank()
                && properties.secretKey() != null && !properties.secretKey().isBlank();
        if (configured) {
            storage.delete(objectKey);
        }
    }
}
