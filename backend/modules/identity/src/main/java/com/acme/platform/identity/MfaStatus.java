package com.acme.platform.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** MFA 方式状态：pending 尚未确认，active 可用于登录，disabled 只能重新注册。 */
public enum MfaStatus {

    PENDING("pending"),
    ACTIVE("active"),
    DISABLED("disabled");

    private final String wire;

    MfaStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static MfaStatus fromWire(String value) {
        for (MfaStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知 MFA 状态：" + value);
    }
}
