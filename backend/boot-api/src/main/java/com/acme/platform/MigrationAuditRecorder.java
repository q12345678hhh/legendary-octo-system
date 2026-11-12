package com.acme.platform;

import com.acme.platform.platform.operations.PlatformOperationStore;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动后把当前 Flyway 版本写入平台运行记录。
 *
 * <p>不直接查询 {@code flyway_schema_history}：应用账号没有该表的读权限是刻意的，
 * 用 Flyway 自己的元数据接口可以避免为此放宽授权。
 */
@Component
@Order(20)
class MigrationAuditRecorder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationAuditRecorder.class);

    private final ObjectProvider<Flyway> flywayProvider;
    private final PlatformOperationStore operationStore;

    MigrationAuditRecorder(ObjectProvider<Flyway> flywayProvider,
                           PlatformOperationStore operationStore) {
        this.flywayProvider = flywayProvider;
        this.operationStore = operationStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            return;
        }
        try {
            MigrationInfo current = flyway.info().current();
            String summary = current == null
                    ? "Flyway 无已应用迁移"
                    : "Flyway 版本 " + current.getVersion() + "（" + current.getDescription() + "）";
            String latest = operationStore.latest("migration").map(run -> run.summary()).orElse(null);
            if (summary.equals(latest)) {
                return;
            }
            operationStore.insert("migration", null, "succeeded", summary, null, null, null);
        } catch (RuntimeException failure) {
            log.warn("写入迁移运行记录失败（不影响启动）：{}", failure.getMessage());
        }
    }
}
