package com.acme.platform.organization;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 组织与生效日期配置。
 *
 * <p>预览票据有效期与委派上限是业务口径，不能写死在代码里：不同租户的组织规模差异很大，
 * 改口径不应该重新发版。到期扫描间隔由 {@code @Scheduled} 的占位符单独控制。
 */
@ConfigurationProperties(prefix = "platform.organization")
public class OrganizationProperties {

    private Duration previewTtl = Duration.ofMinutes(10);
    private int maxDelegationDays = 180;

    public Duration getPreviewTtl() {
        return previewTtl;
    }

    public void setPreviewTtl(Duration previewTtl) {
        this.previewTtl = previewTtl;
    }

    public int getMaxDelegationDays() {
        return maxDelegationDays;
    }

    public void setMaxDelegationDays(int maxDelegationDays) {
        this.maxDelegationDays = maxDelegationDays;
    }
}
