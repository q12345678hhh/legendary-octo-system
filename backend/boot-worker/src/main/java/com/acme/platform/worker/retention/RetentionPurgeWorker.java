package com.acme.platform.worker.retention;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.directory.PlatformDirectoryStore;
import com.acme.platform.platform.operations.PlatformOperationStore;
import com.acme.platform.tenancy.retention.RetentionService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站到期清理。
 *
 * <p>逐租户在租户事务内执行，先删对象再删元数据；失败只记日志，下一轮重试，
 * 不会出现「行没了对象还在」。
 */
@Component
public class RetentionPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeWorker.class);
    private static final int BATCH = 200;

    private final PlatformDirectoryStore directoryStore;
    private final RetentionService retention;
    private final PlatformOperationStore operations;

    public RetentionPurgeWorker(PlatformDirectoryStore directoryStore,
                                RetentionService retention,
                                PlatformOperationStore operations) {
        this.directoryStore = directoryStore;
        this.retention = retention;
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${platform.retention.purge-interval:1h}",
            initialDelayString = "${platform.retention.purge-initial-delay:5m}")
    public void purgeDue() {
        int total = 0;
        for (UUID tenantId : directoryStore.tenantIds()) {
            try {
                RequestContext context = new RequestContext(tenantId, "retention-worker",
                        "retention-purge", UUID.randomUUID().toString().replace("-", ""));
                total += retention.purgeDue(context, BATCH);
            } catch (RuntimeException failure) {
                log.warn("租户 {} 回收站清理失败：{}", tenantId, failure.getMessage());
            }
        }
        if (total > 0) {
            operations.insert("retention_purge", null, "succeeded",
                    "purged=" + total, null, "retention-worker",
                    UUID.randomUUID().toString().replace("-", ""));
            log.info("回收站清理：{} 条", total);
        }
    }
}
