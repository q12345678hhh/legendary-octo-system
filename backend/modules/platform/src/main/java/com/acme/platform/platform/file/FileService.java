package com.acme.platform.platform.file;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.storage.ObjectStorageClient;
import com.acme.platform.platform.storage.ObjectStorageProperties;
import com.acme.platform.platform.storage.S3Presigner;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 文件应用服务：上传只发预签名 URL，下载必须重新授权。
 *
 * <p>对象键格式是 {@code tenantId/fileId/fileName}：即使预签名 URL 泄露，
 * 键本身也带着租户前缀，便于审计与生命周期策略按租户清理。
 */
@Service
public class FileService {

    static final String FILE_UPLOADED_EVENT = "platform.file.uploaded.v1";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final long MAX_SIZE_BYTES = 100L * 1024 * 1024;

    public record UploadSession(UUID fileId, String objectKey, String uploadUrl, OffsetDateTime expiresAt) {
    }

    public record DownloadGrant(UUID fileId, String url, OffsetDateTime expiresAt) {
    }

    private final FileStore store;
    private final TenantTransactions transactions;
    private final OutboxEventPublisher publisher;
    private final S3Presigner presigner;
    private final ObjectStorageClient storage;
    private final ObjectStorageProperties properties;

    public FileService(FileStore store,
                       TenantTransactions transactions,
                       OutboxEventPublisher publisher,
                       S3Presigner presigner,
                       ObjectStorageClient storage,
                       ObjectStorageProperties properties) {
        this.store = store;
        this.transactions = transactions;
        this.publisher = publisher;
        this.presigner = presigner;
        this.storage = storage;
        this.properties = properties;
    }

    public UploadSession createUploadSession(String fileName, String contentType,
                                             long sizeBytes, String sha256) {
        RequestContext context = requireIdentity();
        String safeName = validate(fileName, contentType, sizeBytes, sha256);
        UUID fileId = UUID.randomUUID();
        String objectKey = context.tenantId() + "/" + fileId + "/" + safeName;

        transactions.inTenant(context, ctx -> store.insert(fileId, ctx.tenantId(), ctx.subjectId(),
                safeName, contentType.strip(), sizeBytes, sha256 == null ? null : sha256.toLowerCase(),
                objectKey));

        String url = presigner.presignPut(objectKey, properties.uploadTtl());
        return new UploadSession(fileId, objectKey, url,
                OffsetDateTime.now().plus(properties.uploadTtl()));
    }

    public FileRecord complete(UUID fileId) {
        RequestContext context = requireIdentity();
        FileRecord file = transactions.inTenant(context, ctx -> store.find(fileId)
                .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "文件不存在或不属于当前租户")));
        if (!file.subjectId().equals(context.subjectId())) {
            throw ApiException.forbidden("FILE_NOT_OWNED", "文件不属于当前主体");
        }
        if (file.uploadedAt() != null || "available".equals(file.status())) {
            return file;
        }
        if (!"pending_upload".equals(file.status())) {
            throw ApiException.conflict("FILE_STATE_INVALID", "文件当前状态不允许完成上传：" + file.status());
        }
        if (!storage.exists(file.objectKey())) {
            throw ApiException.conflict("FILE_UPLOAD_MISSING", "对象存储中找不到已上传的对象");
        }

        return transactions.inTenant(context, ctx -> {
            if (!store.markUploaded(fileId)) {
                return store.find(fileId).orElse(file);
            }
            FileRecord uploaded = store.find(fileId).orElseThrow();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("file_id", fileId.toString());
            event.put("object_key", uploaded.objectKey());
            event.put("content_type", uploaded.contentType());
            event.put("size_bytes", uploaded.sizeBytes());
            event.put("subject_id", uploaded.subjectId());
            publisher.publish(FILE_UPLOADED_EVENT, event);
            return uploaded;
        });
    }

    public List<FileRecord> list(int limit) {
        RequestContext context = requireIdentity();
        int bounded = Math.max(1, Math.min(limit, 100));
        return transactions.inTenant(context, ctx -> store.list(bounded));
    }

    public DownloadGrant authorizeDownload(UUID fileId) {
        RequestContext context = requireIdentity();
        FileRecord file = transactions.inTenant(context, ctx -> store.find(fileId)
                .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "文件不存在或不属于当前租户")));
        if (!file.subjectId().equals(context.subjectId())) {
            throw ApiException.forbidden("FILE_NOT_OWNED", "文件不属于当前主体，不能复用他人下载链接");
        }
        if (!"clean".equals(file.scanStatus())) {
            throw ApiException.conflict("FILE_NOT_SCANNED",
                    "文件扫描状态为 " + file.scanStatus() + "，只有 clean 才允许下载");
        }
        if (!"available".equals(file.status())) {
            throw ApiException.conflict("FILE_NOT_AVAILABLE", "文件当前状态为 " + file.status());
        }
        String url = presigner.presignGet(file.objectKey(), properties.downloadTtl());
        return new DownloadGrant(fileId, url, OffsetDateTime.now().plus(properties.downloadTtl()));
    }

    private static String validate(String fileName, String contentType, long sizeBytes, String sha256) {
        String name = fileName == null ? "" : fileName.strip();
        if (name.isEmpty() || name.length() > 200) {
            throw ApiException.badRequest("FILE_NAME_INVALID", "文件名必填且不超过 200 字符");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("FILE_NAME_INVALID", "文件名不能包含控制字符");
        }
        String safe = name.replace('\\', '_').replace('/', '_');
        if (contentType == null || contentType.isBlank() || contentType.length() > 120) {
            throw ApiException.badRequest("FILE_CONTENT_TYPE_INVALID", "Content-Type 必填且不超过 120 字符");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) {
            throw ApiException.badRequest("FILE_SIZE_INVALID", "文件大小必须在 1 字节到 100MB 之间");
        }
        if (sha256 != null && !sha256.isBlank() && !SHA256.matcher(sha256.toLowerCase()).matches()) {
            throw ApiException.badRequest("FILE_SHA256_INVALID", "sha256 必须是 64 位十六进制");
        }
        return safe;
    }

    private static RequestContext requireIdentity() {
        RequestContext context = RequestContextHolder.current();
        if (context == null || context.tenantId() == null || context.subjectId() == null) {
            throw ApiException.badRequest("IDENTITY_REQUIRED", "请求必须携带 X-Tenant-Id 与 X-Subject-Id");
        }
        return context;
    }
}
