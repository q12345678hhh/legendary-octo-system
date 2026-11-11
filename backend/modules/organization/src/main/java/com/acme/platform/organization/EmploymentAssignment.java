package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 主/兼岗：一段雇佣在某个职位上的占用区间。 */
public record EmploymentAssignment(UUID id,
                                   UUID tenantId,
                                   UUID employmentId,
                                   UUID positionId,
                                   AssignmentType assignmentType,
                                   LocalDate effectiveFrom,
                                   LocalDate effectiveTo,
                                   OffsetDateTime createdAt) {

    public enum AssignmentType {
        PRIMARY,
        SECONDARY;

        @JsonCreator
        public static AssignmentType fromWire(String value) {
            return "secondary".equalsIgnoreCase(value) ? SECONDARY : PRIMARY;
        }

        @JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }
}
