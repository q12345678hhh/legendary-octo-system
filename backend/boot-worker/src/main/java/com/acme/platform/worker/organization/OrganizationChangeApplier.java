package com.acme.platform.worker.organization;

import com.acme.platform.organization.OrganizationChangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 组织变更执行器：周期扫描到期变更并在各租户上下文里应用。
 *
 * <p>租户表启用了 FORCE RLS，Worker 没有租户上下文时读不到任何行，
 * 因此到期发现走非 RLS 的系统队列 {@code platform_organization_due}；
 * 真正的加锁、校验与切换仍然在租户事务里完成。
 */
@Component
public class OrganizationChangeApplier {

    private static final Logger log = LoggerFactory.getLogger(OrganizationChangeApplier.class);

    /** 单轮最多处理多少条：组织调整是低频写入，50 条足够且在单轮内保持可控。 */
    private static final int BATCH_SIZE = 50;

    private final OrganizationChangeService changeService;

    public OrganizationChangeApplier(OrganizationChangeService changeService) {
        this.changeService = changeService;
    }

    @Scheduled(fixedDelayString = "${platform.organization.change-poll-interval:30s}")
    public void poll() {
        int applied = applyOnce();
        if (applied > 0) {
            log.info("本轮应用 {} 条到期组织变更", applied);
        }
    }

    /** 单独暴露一轮处理，便于测试与运维手动驱动。 */
    public int applyOnce() {
        return changeService.applyDue(BATCH_SIZE);
    }
}
