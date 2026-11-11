package com.acme.platform.worker.file;

import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.file.FileScanService;
import com.acme.platform.worker.outbox.OutboxEvent;
import com.acme.platform.worker.outbox.OutboxEventHandler;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 消费 {@code platform.file.uploaded.v1}，执行扫描并更新文件状态。
 *
 * <p>扫描失败或被判定 infected 时文件不会变成 available，下载接口会一直拒绝；
 * 这是刻意的「默认不安全」：上传成功不等于可下载。
 */
@Component
class FileUploadedEventHandler implements OutboxEventHandler {

    static final String EVENT_NAME = "platform.file.uploaded.v1";

    private static final Logger log = LoggerFactory.getLogger(FileUploadedEventHandler.class);

    private final FileScanService scanService;
    private final TenantTransactions transactions;
    private final ObjectMapper objectMapper;

    FileUploadedEventHandler(FileScanService scanService, TenantTransactions transactions,
                             ObjectMapper objectMapper) {
        this.scanService = scanService;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventName() {
        return EVENT_NAME;
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID fileId = UUID.fromString(objectMapper.readTree(event.payload()).path("file_id").asString());
        transactions.inTenant(ctx -> scanService.scan(fileId));
        log.info("文件扫描事件处理完成 fileId={}", fileId);
    }
}
