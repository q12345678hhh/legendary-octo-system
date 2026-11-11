package com.acme.platform.organization;

import com.acme.platform.tenancy.TenantProfile;
import com.acme.platform.tenancy.TenantStore;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 组织侧「当日」换算。
 *
 * <p>生效日期是业务日期，不是 UTC 日期：跨时区租户在本地零点前后提交的变更
 * 必须落在同一个业务日，否则会出现「同一份申请，两个人看到不同的生效结果」。
 * 租户档案没有时区或时区非法时回退 UTC，绝不因为配置缺失而拒绝业务写入。
 */
@Component
public class OrganizationCalendar {

    private final TenantStore tenantStore;
    private final Clock clock;

    public OrganizationCalendar(TenantStore tenantStore, Clock clock) {
        this.tenantStore = tenantStore;
        this.clock = clock;
    }

    public LocalDate today(UUID tenantId) {
        String timezone = tenantStore.findProfile(tenantId)
                .map(TenantProfile::timezone)
                .filter(value -> value != null && !value.isBlank())
                .orElse("UTC");
        try {
            return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
        } catch (DateTimeException e) {
            return LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC));
        }
    }
}
