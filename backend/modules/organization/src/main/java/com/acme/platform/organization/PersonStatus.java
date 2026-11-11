package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 自然人状态：不因账号停用而改变，账号只是其中一种关联。 */
public enum PersonStatus {

    ACTIVE("active"),
    INACTIVE("inactive");

    private final String wire;

    PersonStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static PersonStatus fromWire(String value) {
        for (PersonStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知自然人状态：" + value);
    }
}
