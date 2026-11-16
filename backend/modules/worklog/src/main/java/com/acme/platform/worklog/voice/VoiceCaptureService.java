package com.acme.platform.worklog.voice;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.storage.ObjectStorageClient;
import com.acme.platform.platform.storage.ObjectStorageProperties;
import com.acme.platform.platform.storage.S3Presigner;
import com.acme.platform.worklog.time.WorklogEntry;
import com.acme.platform.worklog.time.WorklogEntryService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 语音工时录入：签发上传地址、调用转写、确认生成工时并删除音频。
 *
 * <p>音频只用于一次转写；确认后立即删除对象存储文件。转写失败不会伪造文本，
 * 也不会在未配置 ASR 时读取对象存储。
 */
@Service
public class VoiceCaptureService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "audio/webm", "audio/ogg", "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav");

    private final VoiceCaptureStore store;
    private final SpeechTranscriber transcriber;
    private final ObjectStorageClient storage;
    private final S3Presigner presigner;
    private final ObjectStorageProperties storageProperties;
    private final AsrProperties asrProperties;
    private final WorklogEntryService entries;
    private final TenantTransactions transactions;

    public VoiceCaptureService(VoiceCaptureStore store, SpeechTranscriber transcriber,
                               ObjectStorageClient storage, S3Presigner presigner,
                               ObjectStorageProperties storageProperties,
                               AsrProperties asrProperties, WorklogEntryService entries,
                               TenantTransactions transactions) {
        this.store = store;
        this.transcriber = transcriber;
        this.storage = storage;
        this.presigner = presigner;
        this.storageProperties = storageProperties;
        this.asrProperties = asrProperties;
        this.entries = entries;
        this.transactions = transactions;
    }

    public record CreateUploadInput(LocalDate workDate, String contentType, long sizeBytes,
                                    Integer durationSeconds, String clientRequestId) {
    }

    public record UploadTicket(VoiceCapture capture, String uploadUrl, java.time.Instant expiresAt) {
    }

    public record ConfirmInput(String text, Integer durationMinutes, OffsetDateTime startsAt,
                               OffsetDateTime endsAt, UUID projectId, UUID taskId,
                               String description, String clientEntryId) {
    }

    public record ConfirmResult(WorklogEntry entry, boolean audioDeleted) {
    }

    public UploadTicket createUpload(UUID personId, UUID employmentId, CreateUploadInput input) {
        String contentType = normalizeType(input.contentType());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw ApiException.badRequest("VOICE_CONTENT_TYPE_UNSUPPORTED",
                    "只支持 webm/ogg/mpeg/mp4/wav 音频");
        }
        if (input.sizeBytes() < 1 || input.sizeBytes() > MAX_BYTES) {
            throw ApiException.badRequest("VOICE_AUDIO_TOO_LARGE", "单次录音不能超过 10MB");
        }
        if (input.durationSeconds() != null
                && (input.durationSeconds() < 1 || input.durationSeconds() > 120)) {
            throw ApiException.badRequest("VOICE_DURATION_INVALID", "单次录音不能超过 120 秒");
        }
        if (input.clientRequestId() == null || input.clientRequestId().isBlank()) {
            throw ApiException.badRequest("VOICE_REQUEST_ID_REQUIRED",
                    "缺少客户端请求标识，无法保证重复提交幂等");
        }
        LocalDate workDate = input.workDate();
        if (workDate == null) {
            throw ApiException.badRequest("WORK_DATE_REQUIRED", "缺少工作日期");
        }
        return transactions.inTenant(context -> {
            VoiceCapture capture = store.findByClientRequest(personId, input.clientRequestId())
                    .orElseGet(() -> {
                        UUID id = UUID.randomUUID();
                        String objectKey = context.tenantId() + "/voice/" + id;
                        return store.insert(personId, employmentId, workDate, objectKey,
                                contentType, input.sizeBytes(), input.durationSeconds(),
                                input.clientRequestId());
                    });
            String uploadUrl = presigner.presignPut(capture.objectKey(),
                    storageProperties.uploadTtl());
            return new UploadTicket(capture, uploadUrl,
                    java.time.Instant.now().plus(storageProperties.uploadTtl()));
        });
    }

    public VoiceCapture transcribe(UUID personId, UUID captureId) {
        if (!transcriber.configured()) {
            throw new ApiException(503, "ASR_NOT_CONFIGURED",
                    "语音转写未配置，请改用文字录入或联系管理员配置 ASR");
        }
        VoiceCapture capture = transactions.inTenant(context -> requireOwned(personId, captureId));
        if ("transcribed".equals(capture.status()) || "confirmed".equals(capture.status())) {
            return capture;
        }
        if (!"uploaded".equals(capture.status()) && !"failed".equals(capture.status())) {
            throw ApiException.conflict("VOICE_CAPTURE_NOT_TRANSCRIBABLE", "该录音已结束，无法转写");
        }
        if (!storage.exists(capture.objectKey())) {
            throw ApiException.conflict("VOICE_UPLOAD_INCOMPLETE", "音频尚未上传完成，请重试");
        }
        byte[] audio;
        try {
            audio = storage.download(capture.objectKey(), asrProperties.maxAudioBytes());
        } catch (IllegalStateException e) {
            throw new ApiException(502, "VOICE_AUDIO_UNAVAILABLE", "无法读取录音，请重新录制");
        }
        SpeechTranscriber.Transcription transcription;
        try {
            transcription = transcriber.transcribe(audio, capture.contentType());
        } catch (ApiException e) {
            transactions.inTenant(context -> {
                store.markFailed(captureId, e.code());
                return null;
            });
            throw e;
        }
        return transactions.inTenant(context -> {
            store.markTranscribed(captureId, transcription.text(), transcription.provider(),
                    transcription.model(), transcription.confidence());
            return store.find(captureId).orElseThrow();
        });
    }

    public ConfirmResult confirm(UUID personId, UUID captureId, ConfirmInput input) {
        return transactions.inTenant(context -> {
            VoiceCapture capture = requireOwned(personId, captureId);
            if (capture.worklogEntryId() != null) {
                return new ConfirmResult(entries.get(personId, capture.worklogEntryId()), true);
            }
            if (!"transcribed".equals(capture.status())) {
                throw ApiException.conflict("VOICE_NOT_TRANSCRIBED", "请先完成语音转写");
            }
            String text = input.text() == null || input.text().isBlank()
                    ? capture.transcriptText() : input.text().strip();
            if (text == null || text.isBlank()) {
                throw ApiException.unprocessable("VOICE_TEXT_REQUIRED", "确认前需要填写工时说明");
            }
            String description = input.description() == null || input.description().isBlank()
                    ? text : input.description();
            Integer minutes = input.durationMinutes() == null
                    ? defaultMinutes(capture.durationSeconds()) : input.durationMinutes();
            WorklogEntry entry = entries.create(personId, capture.employmentId(),
                    new WorklogEntryService.EntryInput(capture.workDate(), input.startsAt(),
                            input.endsAt(), minutes, input.projectId(), input.taskId(),
                            description, input.clientEntryId()), "voice");
            store.markConfirmed(captureId, text, entry.id());
            boolean deleted = deleteAudio(capture);
            if (deleted) {
                store.markAudioDeleted(captureId);
            }
            return new ConfirmResult(entry, deleted);
        });
    }

    public void discard(UUID personId, UUID captureId) {
        transactions.inTenant(context -> {
            VoiceCapture capture = requireOwned(personId, captureId);
            if ("confirmed".equals(capture.status())) {
                throw ApiException.conflict("VOICE_ALREADY_CONFIRMED",
                        "该录音已生成工时，不能直接放弃");
            }
            if (deleteAudio(capture)) {
                store.markAudioDeleted(captureId);
            }
            store.markDiscarded(captureId);
            return null;
        });
    }

    private VoiceCapture requireOwned(UUID personId, UUID captureId) {
        VoiceCapture capture = store.find(captureId).orElseThrow(() ->
                ApiException.notFound("VOICE_CAPTURE_NOT_FOUND", "录音不存在：" + captureId));
        if (!capture.personId().equals(personId)) {
            throw ApiException.forbidden("VOICE_NOT_OWNER", "只能操作自己的录音");
        }
        return capture;
    }

    private boolean deleteAudio(VoiceCapture capture) {
        try {
            storage.delete(capture.objectKey());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Integer defaultMinutes(Integer durationSeconds) {
        if (durationSeconds == null) {
            return null;
        }
        return Math.max(1, (int) Math.ceil(durationSeconds / 60.0));
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return "";
        }
        int semicolon = raw.indexOf(';');
        String value = semicolon >= 0 ? raw.substring(0, semicolon) : raw;
        return value.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
