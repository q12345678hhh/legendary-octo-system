package com.acme.platform.worker.authorization;

import com.acme.platform.authorization.AccessReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 季度访问复核执行器：生成当季复核，并执行到期升级与宽限期后的自动暂停。
 *
 * <p>跨租户发现依赖非 RLS 的 {@code platform_access_review_due}；
 * 所有业务写入都在各自租户事务里完成。
 */
@Component
public class AccessReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(AccessReviewWorker.class);

    private static final int GENERATE_BATCH = 20;
    private static final int SWEEP_BATCH = 50;

    private final AccessReviewService reviewService;

    public AccessReviewWorker(AccessReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Scheduled(fixedDelayString = "${platform.authorization.review-poll-interval:5m}")
    public void poll() {
        int generated = reviewService.generateDue(GENERATE_BATCH);
        int revoked = reviewService.processOverdue(SWEEP_BATCH);
        if (generated > 0 || revoked > 0) {
            log.info("季度访问复核：生成 {} 个条目，自动暂停 {} 条授权", generated, revoked);
        }
    }
}
