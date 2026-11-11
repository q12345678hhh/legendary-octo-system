package com.acme.platform.tenancy;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 租户生命周期状态。
 *
 * <p>合法迁移只有以下五条路径，其余一律视为冲突而不是幂等：
 * <pre>
 * provisioning → active | deleted
 * active       → frozen | closing
 * frozen       → active | closing
 * closing      → active（冷静期内撤回） | deleted
 * deleted      → 终态
 * </pre>
 *
 * <p>冻结与注销申请都会阻止业务写入、插件与 AI 新执行；只有 {@link #ACTIVE} 可写可执行。
 */
public enum TenantStatus {

    PROVISIONING("provisioning"),
    ACTIVE("active"),
    FROZEN("frozen"),
    CLOSING("closing"),
    DELETED("deleted");

    private final String wire;

    TenantStatus(String wire) {
        this.wire = wire;
    }

    /** 对外统一使用小写状态码，避免枚举常量名泄漏到契约。 */
    @JsonValue
    public String wire() {
        return wire;
    }

    public boolean writable() {
        return this == ACTIVE;
    }

    public boolean executable() {
        return this == ACTIVE;
    }

    public boolean canTransitionTo(TenantStatus target) {
        return switch (this) {
            case PROVISIONING -> target == ACTIVE || target == DELETED;
            case ACTIVE -> target == FROZEN || target == CLOSING;
            case FROZEN -> target == ACTIVE || target == CLOSING;
            case CLOSING -> target == ACTIVE || target == DELETED;
            case DELETED -> false;
        };
    }

    public static TenantStatus fromWire(String value) {
        for (TenantStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的租户状态：" + value);
    }
}
