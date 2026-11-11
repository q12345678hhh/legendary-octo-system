package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 雇佣关系状态：结束一段雇佣不改写历史，再雇佣新开一段。 */
public enum EmploymentStatus {

    ACTIVE("active"),
    ENDED("ended");

    private final String wire;

    EmploymentStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static EmploymentStatus fromWire(String value) {
        for (EmploymentStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知雇佣状态：" + value);
    }
}
