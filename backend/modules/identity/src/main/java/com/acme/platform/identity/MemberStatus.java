package com.acme.platform.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 租户成员状态；与账号状态分开，便于换账号时保持成员与雇佣不变。 */
public enum MemberStatus {

    INVITED("invited"),
    ACTIVE("active"),
    SUSPENDED("suspended"),
    DISABLED("disabled");

    private final String wire;

    MemberStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static MemberStatus fromWire(String value) {
        for (MemberStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知成员状态：" + value);
    }
}
