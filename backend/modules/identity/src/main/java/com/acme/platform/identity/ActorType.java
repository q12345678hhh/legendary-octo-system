package com.acme.platform.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 统一主体类型；当前实现 user 与 service，ai/external 为后续工作包预留。 */
public enum ActorType {

    USER("user"),
    SERVICE("service"),
    AI("ai"),
    EXTERNAL("external");

    private final String wire;

    ActorType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ActorType fromWire(String value) {
        for (ActorType type : values()) {
            if (type.wire.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知主体类型：" + value);
    }
}
