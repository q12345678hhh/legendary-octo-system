package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 老板（owner）与受托人（trustee）：独立业务角色，与租户管理员无关。 */
public record OwnerRole(UUID id,
                        UUID tenantId,
                        UUID employmentId,
                        RoleType roleType,
                        LocalDate effectiveFrom,
                        LocalDate effectiveTo,
                        String appointedBy,
                        OffsetDateTime createdAt) {

    public enum RoleType {
        OWNER,
        TRUSTEE;

        @JsonCreator
        public static RoleType fromWire(String value) {
            return "trustee".equalsIgnoreCase(value) ? TRUSTEE : OWNER;
        }

        @JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }
}
