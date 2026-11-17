package com.acme.platform.tenancy.retention;

import com.acme.platform.platform.settings.PlatformSettingService;
import org.springframework.stereotype.Component;

/**
 * 保留策略读取：三个键都由平台设置在写入口校验合规下限。
 *
 * <p>下限常量与 {@code PlatformSettingCatalog} 保持一致；这里再做一次兜底，
 * 防止设置表被手工改动后读到低于合规要求的数值。
 */
@Component
public class RetentionPolicy {

    public static final int AUDIT_MIN_DAYS = 2555;
    public static final int BUSINESS_LOG_MIN_DAYS = 90;
    public static final int RECYCLE_BIN_MIN_DAYS = 7;

    private final PlatformSettingService settings;

    public RetentionPolicy(PlatformSettingService settings) {
        this.settings = settings;
    }

    public int recycleBinDays() {
        return Math.max(RECYCLE_BIN_MIN_DAYS, settings.intLimit("retention.recycle_bin_days", 30));
    }

    public int businessLogDays() {
        return Math.max(BUSINESS_LOG_MIN_DAYS, settings.intLimit("retention.business_log_days", 180));
    }

    public int auditDays() {
        return Math.max(AUDIT_MIN_DAYS, settings.intLimit("retention.audit_days", 2555));
    }
}
