package com.acme.platform.worklog.voice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 语音录入记录的 JDBC 存储；调用方必须处于租户事务内。 */
@Component
public class VoiceCaptureStore {

    private static final String COLUMNS = """
            id, person_id, employment_id, work_date, object_key, content_type, size_bytes,
            duration_seconds, status, transcript_text, edited_text, provider, model, confidence,
            error_code, worklog_entry_id, transcribed_at, audio_deleted_at, created_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public VoiceCaptureStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public VoiceCapture insert(UUID personId, UUID employmentId, LocalDate workDate,
                               String objectKey, String contentType, long sizeBytes,
                               Integer durationSeconds, String clientRequestId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO worklog_voice_capture
                    (tenant_id, person_id, employment_id, work_date, object_key, content_type,
                     size_bytes, duration_seconds, client_request_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING %s
                """.formatted(COLUMNS), VoiceCaptureStore::map,
                personId, employmentId, workDate, objectKey, contentType, sizeBytes,
                durationSeconds, clientRequestId);
    }

    public Optional<VoiceCapture> find(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM worklog_voice_capture"
                + " WHERE id = ? AND recycled_at IS NULL",
                VoiceCaptureStore::map, id).stream().findFirst();
    }

    public Optional<VoiceCapture> findByClientRequest(UUID personId, String clientRequestId) {
        return jdbcTemplate.query("""
                        SELECT %s FROM worklog_voice_capture
                         WHERE person_id = ? AND client_request_id = ? AND recycled_at IS NULL
                        """.formatted(COLUMNS), VoiceCaptureStore::map,
                personId, clientRequestId).stream().findFirst();
    }

    /** 含回收站中的记录，供数据主体删除校验归属。 */
    public Optional<VoiceCapture> findIncludingRecycled(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM worklog_voice_capture"
                + " WHERE id = ?", VoiceCaptureStore::map, id).stream().findFirst();
    }

    /** 本人员工可见的语音记录（不含已进回收站的）。 */
    public java.util.List<VoiceCapture> listByPerson(UUID personId, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM worklog_voice_capture"
                + " WHERE person_id = ? AND recycled_at IS NULL"
                + " ORDER BY created_at DESC LIMIT ?",
                VoiceCaptureStore::map, personId, limit);
    }

    public void markRecycled(UUID id) {
        jdbcTemplate.update("""
                UPDATE worklog_voice_capture SET recycled_at = now()
                 WHERE id = ? AND recycled_at IS NULL
                """, id);
    }

    public void restore(UUID id) {
        jdbcTemplate.update("""
                UPDATE worklog_voice_capture SET recycled_at = NULL
                 WHERE id = ?
                """, id);
    }

    public void delete(UUID id) {
        jdbcTemplate.update("DELETE FROM worklog_voice_capture WHERE id = ?", id);
    }

    public Optional<String> objectKey(UUID id) {
        return jdbcTemplate.query(
                        "SELECT object_key FROM worklog_voice_capture WHERE id = ?",
                        (rs, rowNum) -> rs.getString("object_key"), id)
                .stream().findFirst();
    }

    public void markTranscribed(UUID id, String transcriptText, String provider, String model,
                                Double confidence) {
        jdbcTemplate.update("""
                UPDATE worklog_voice_capture
                   SET status = 'transcribed', transcript_text = ?, edited_text = ?,
                       provider = ?, model = ?, confidence = ?, error_code = NULL,
                       transcribed_at = now()
                 WHERE id = ?
                """, transcriptText, transcriptText, provider, model,
                confidence == null ? null : BigDecimal.valueOf(confidence), id);
    }

    public void markFailed(UUID id, String errorCode) {
        jdbcTemplate.update("""
                UPDATE worklog_voice_capture
                   SET status = 'failed', error_code = ?
                 WHERE id = ?
                """, errorCode, id);
    }

    public void markConfirmed(UUID id, String editedText, UUID worklogEntryId) {
        jdbcTemplate.update("""
                UPDATE worklog_voice_capture
                   SET status = 'confirmed', edited_text = ?, worklog_entry_id = ?
                 WHERE id = ?
                """, editedText, worklogEntryId, id);
    }

    public void markDiscarded(UUID id) {
        jdbcTemplate.update("""
                UPDATE worklog_voice_capture SET status = 'discarded' WHERE id = ?
                """, id);
    }

    public void markAudioDeleted(UUID id) {
        jdbcTemplate.update("""
                UPDATE worklog_voice_capture SET audio_deleted_at = now() WHERE id = ?
                """, id);
    }

    private static VoiceCapture map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        BigDecimal confidence = rs.getObject("confidence", BigDecimal.class);
        return new VoiceCapture(
                rs.getObject("id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getObject("employment_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getString("object_key"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                (Integer) rs.getObject("duration_seconds"),
                rs.getString("status"),
                rs.getString("transcript_text"),
                rs.getString("edited_text"),
                rs.getString("provider"),
                rs.getString("model"),
                confidence == null ? null : confidence.doubleValue(),
                rs.getString("error_code"),
                rs.getObject("worklog_entry_id", UUID.class),
                rs.getObject("transcribed_at", OffsetDateTime.class),
                rs.getObject("audio_deleted_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
