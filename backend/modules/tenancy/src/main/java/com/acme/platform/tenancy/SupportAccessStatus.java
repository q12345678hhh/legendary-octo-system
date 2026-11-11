package com.acme.platform.tenancy;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 平台支持访问状态。
 */
public enum SupportAccessStatus {

    PENDING("pending"),
    APPROVED("approved"),
    DENIED("denied"),
    EXPIRED("expired"),
    REVOKED("revoked");

    private final String wire;

    SupportAccessStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public static SupportAccessStatus fromWire(String value) {
        for (SupportAccessStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的支持访问状态：" + value);
    }
}
