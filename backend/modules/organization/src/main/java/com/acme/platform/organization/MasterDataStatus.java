package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 主数据状态：active 可用，inactive 停用但不删除历史引用。 */
public enum MasterDataStatus {
    ACTIVE,
    INACTIVE;

    @JsonCreator
    public static MasterDataStatus fromWire(String value) {
        return "inactive".equalsIgnoreCase(value) ? INACTIVE : ACTIVE;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }
}
