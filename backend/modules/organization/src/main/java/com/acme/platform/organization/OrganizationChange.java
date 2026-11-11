package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 未来变更单：只有 scheduled 可以被应用。 */
public record OrganizationChange(UUID id,
                                 UUID tenantId,
                                 ChangeType changeType,
                                 UUID targetId,
                                 LocalDate effectiveDate,
                                 Map<String, Object> payload,
                                 ChangeStatus status,
                                 String requestedBy,
                                 String reason,
                                 OffsetDateTime appliedAt,
                                 String failureReason,
                                 OffsetDateTime createdAt) {

    public enum ChangeType {
        UNIT_CREATE,
        UNIT_UPDATE,
        UNIT_MOVE,
        UNIT_ARCHIVE,
        LEGAL_ENTITY_UPSERT,
        JOB_UPSERT,
        JOB_LEVEL_UPSERT,
        POSITION_UPSERT,
        COST_CENTER_UPSERT,
        LOCATION_UPSERT,
        OWNER_ASSIGN,
        OWNER_REVOKE;

        @JsonCreator
        public static ChangeType fromWire(String value) {
            try {
                return ChangeType.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException error) {
                throw com.acme.platform.platform.ApiException.unprocessable("CHANGE_TYPE_INVALID",
                        "未知的变更类型：" + value);
            }
        }

        @JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }

    public enum ChangeStatus {
        SCHEDULED,
        APPLIED,
        CANCELLED,
        FAILED;

        @JsonCreator
        public static ChangeStatus fromWire(String value) {
            try {
                return ChangeStatus.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException error) {
                throw com.acme.platform.platform.ApiException.unprocessable("CHANGE_STATUS_INVALID",
                        "未知的变更状态：" + value);
            }
        }

        @JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }
}
