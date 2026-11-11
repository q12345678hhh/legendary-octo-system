package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 部门树节点：parentId 为空表示根，type 区分公司/事业部/部门/小组。 */
public record OrgUnit(UUID id,
                      UUID tenantId,
                      UUID parentId,
                      UUID legalEntityId,
                      String code,
                      String name,
                      UnitType unitType,
                      UnitStatus status,
                      int sortOrder,
                      OffsetDateTime createdAt) {

    public enum UnitType {
        COMPANY,
        DIVISION,
        DEPARTMENT,
        TEAM;

        @JsonCreator
        public static UnitType fromWire(String value) {
            if (value == null) {
                return DEPARTMENT;
            }
            return switch (value.toLowerCase()) {
                case "company" -> COMPANY;
                case "division" -> DIVISION;
                case "team" -> TEAM;
                default -> DEPARTMENT;
            };
        }

        @JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }

    public enum UnitStatus {
        ACTIVE,
        ARCHIVED;

        @JsonCreator
        public static UnitStatus fromWire(String value) {
            return "archived".equalsIgnoreCase(value) ? ARCHIVED : ACTIVE;
        }

        @JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }
}
