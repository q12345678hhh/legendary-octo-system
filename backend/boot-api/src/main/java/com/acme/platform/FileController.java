package com.acme.platform;

import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.platform.file.FileRecord;
import com.acme.platform.platform.file.FileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件接口：只发预签名 URL，应用进程不转发文件字节。
 *
 * <p>下载不是「拿到 ID 就能下」：每次都要用当前主体重新授权，
 * 扫描未通过、主体不匹配、租户不匹配都会被拒绝。
 */
@RestController
@RequestMapping("/api/v1/system/files")
class FileController {

    record UploadRequest(
            @NotBlank @Size(max = 200) String fileName,
            @NotBlank @Size(max = 120) String contentType,
            @Min(1) long sizeBytes,
            String sha256) {
    }

    record UploadResponse(UUID fileId, String objectKey, String uploadUrl, OffsetDateTime expiresAt) {
    }

    record FileResponse(
            UUID fileId,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            String status,
            String scanStatus,
            String scanDetail,
            OffsetDateTime createdAt,
            OffsetDateTime uploadedAt,
            OffsetDateTime scannedAt) {

        static FileResponse from(FileRecord file) {
            return new FileResponse(file.id(), file.fileName(), file.contentType(), file.sizeBytes(),
                    file.sha256(), file.status(), file.scanStatus(), file.scanDetail(),
                    file.createdAt(), file.uploadedAt(), file.scannedAt());
        }
    }

    record DownloadResponse(UUID fileId, String url, OffsetDateTime expiresAt) {
    }

    private final FileService service;
    private final AuthorizationGuard authorization;

    FileController(FileService service, AuthorizationGuard authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse createUpload(@Valid @RequestBody UploadRequest request) {
        FileService.UploadSession session = service.createUploadSession(
                request.fileName(), request.contentType(), request.sizeBytes(), request.sha256());
        return new UploadResponse(session.fileId(), session.objectKey(), session.uploadUrl(),
                session.expiresAt());
    }

    @PostMapping("/{fileId}/complete")
    FileResponse complete(@PathVariable UUID fileId) {
        authorise(fileId);
        return FileResponse.from(service.complete(fileId));
    }

    @GetMapping
    List<FileResponse> list(@RequestParam(defaultValue = "20") int limit) {
        return service.list(limit).stream().map(FileResponse::from).toList();
    }

    @GetMapping("/{fileId}/download")
    DownloadResponse download(@PathVariable UUID fileId) {
        authorise(fileId);
        FileService.DownloadGrant grant = service.authorizeDownload(fileId);
        return new DownloadResponse(grant.fileId(), grant.url(), grant.expiresAt());
    }

    /** 文件与人员共用同一决策：资源归属交给范围解析，未授权统一 403。 */
    private void authorise(UUID fileId) {
        FileRecord file = service.find(fileId);
        authorization.require("file.read", "file", fileId, Map.of("ownerId", file.subjectId()));
    }
}
