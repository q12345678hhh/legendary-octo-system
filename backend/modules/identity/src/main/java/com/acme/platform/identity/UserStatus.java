package com.acme.platform.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 登录账号状态；禁用与重置密码都会推进 auth_version。 */
public enum UserStatus {

    INVITED("invited"),
    ACTIVE("active"),
    SUSPENDED("suspended"),
    DISABLED("disabled");

    private final String wire;

    UserStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static UserStatus fromWire(String value) {
        for (UserStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知账号状态：" + value);
    }
}
