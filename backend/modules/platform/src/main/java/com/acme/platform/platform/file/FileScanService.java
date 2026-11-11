package com.acme.platform.platform.file;

import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.PlatformMetrics;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件扫描占位实现：只做类型、大小与文件名规则，不代表真实杀毒。
 *
 * <p>接口形状与真实扫描保持一致：扫描中 → 结论（clean/infected/failed）。
 * 接真实引擎时替换 {@link #judge(FileRecord)}，其余状态机不改。
 */
@Service
public class FileScanService {

    static final String FILE_SCANNED_EVENT = "platform.file.scanned.v1";

    private static final Logger log = LoggerFactory.getLogger(FileScanService.class);
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif",
            "application/pdf", "text/plain", "text/csv", "application/json",
            "application/zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".scr", ".msi", ".dll", ".com", ".pif");

    private final FileStore store;
    private final OutboxEventPublisher publisher;
    private final PlatformMetrics metrics;

    public FileScanService(FileStore store, OutboxEventPublisher publisher, PlatformMetrics metrics) {
        this.store = store;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    /**
     * 扫描一个文件，返回最新记录；文件不存在时返回 null（事件重复投递的正常情况）。
     * 调用方必须已经绑定租户上下文。
     */
    public FileRecord scan(UUID fileId) {
        FileRecord file = store.find(fileId).orElse(null);
        if (file == null) {
            log.warn("扫描事件找不到文件 fileId={}（可能是跨租户或已删除）", fileId);
            return null;
        }
        if (!"pending".equals(file.scanStatus())) {
            log.info("文件已扫描，跳过 fileId={} scanStatus={}", fileId, file.scanStatus());
            return file;
        }
        store.markScanning(fileId);

        Verdict verdict = judge(file);
        String status = verdict.clean() ? "available" : "rejected";
        store.markScanResult(fileId, verdict.scanStatus(), verdict.detail(), status);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("file_id", fileId.toString());
        event.put("scan_status", verdict.scanStatus());
        event.put("status", status);
        event.put("detail", verdict.detail());
        publisher.publish(FILE_SCANNED_EVENT, event);

        metrics.fileScanned(verdict.scanStatus());
        log.info("文件扫描完成 fileId={} scanStatus={} detail={}", fileId, verdict.scanStatus(), verdict.detail());
        return store.find(fileId).orElse(file);
    }

    private Verdict judge(FileRecord file) {
        String lowerName = file.fileName().toLowerCase(Locale.ROOT);
        if (lowerName.contains("eicar")) {
            return new Verdict("infected", "EICAR 测试样本标记命中");
        }
        for (String extension : BLOCKED_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                return new Verdict("infected", "可执行文件类型禁止上传：" + extension);
            }
        }
        String contentType = file.contentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            return new Verdict("infected", "不支持的文件类型：" + contentType);
        }
        if (file.sizeBytes() > 100L * 1024 * 1024) {
            return new Verdict("infected", "文件超过扫描上限");
        }
        return new Verdict("clean", "类型与大小检查通过（占位扫描器）");
    }

    private record Verdict(String scanStatus, String detail) {
        boolean clean() {
            return "clean".equals(scanStatus);
        }
    }
}
