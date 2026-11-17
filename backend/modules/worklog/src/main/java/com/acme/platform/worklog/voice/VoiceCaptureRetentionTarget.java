package com.acme.platform.worklog.voice;

import com.acme.platform.platform.retention.RetentionTarget;
import com.acme.platform.platform.storage.ObjectStorageClient;
import com.acme.platform.platform.storage.ObjectStorageProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 语音转写记录接入统一回收站。
 *
 * <p>快照只保留工时日期、状态与音频删除时间等元数据，绝不写入转写原文；
 * 物理清理时才删除对象存储音频与数据行。
 */
@Component
public class VoiceCaptureRetentionTarget implements RetentionTarget {

    private final JdbcTemplate jdbcTemplate;
    private final VoiceCaptureStore store;
    private final ObjectStorageClient storage;
    private final ObjectStorageProperties properties;

    public VoiceCaptureRetentionTarget(JdbcTemplate jdbcTemplate, VoiceCaptureStore store,
                                       ObjectStorageClient storage,
                                       ObjectStorageProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.store = store;
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String dataClass() {
        return "worklog_voice_capture";
    }

    @Override
    public Optional<Map<String, Object>> snapshot(UUID objectId) {
        return jdbcTemplate.query("""
                        SELECT object_key, work_date, status, provider, created_at, audio_deleted_at
                          FROM worklog_voice_capture
                         WHERE id = ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("objectRef", rs.getString("object_key"));
                    snapshot.put("workDate", String.valueOf(rs.getObject("work_date")));
                    snapshot.put("status", rs.getString("status"));
                    snapshot.put("provider", rs.getString("provider"));
                    snapshot.put("createdAt", String.valueOf(rs.getObject("created_at")));
                    snapshot.put("audioDeletedAt", String.valueOf(
                            rs.getObject("audio_deleted_at")));
                    return snapshot;
                }, objectId)
                .stream().findFirst();
    }

    @Override
    public void markRecycled(UUID objectId) {
        store.markRecycled(objectId);
    }

    @Override
    public void restore(UUID objectId) {
        store.restore(objectId);
    }

    @Override
    public void purge(UUID objectId) {
        store.objectKey(objectId).ifPresent(this::deleteObject);
        store.delete(objectId);
    }

    /** 与文件一致：没有对象存储凭据时只清元数据，避免本地/测试环境误报失败。 */
    private void deleteObject(String objectKey) {
        boolean configured = properties.accessKey() != null && !properties.accessKey().isBlank()
                && properties.secretKey() != null && !properties.secretKey().isBlank();
        if (configured) {
            storage.delete(objectKey);
        }
    }
}
