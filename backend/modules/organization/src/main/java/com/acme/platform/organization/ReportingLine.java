package com.acme.platform.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 汇报线：solid 实线、dotted 虚线。 */
public record ReportingLine(UUID id,
                            UUID tenantId,
                            UUID subordinateEmploymentId,
                            UUID managerEmploymentId,
                            LineType lineType,
                            LocalDate effectiveFrom,
                            LocalDate effectiveTo,
                            String createdBy,
                            OffsetDateTime createdAt) {

    public enum LineType {
        SOLID,
        DOTTED;

        @JsonCreator
        public static LineType fromWire(String value) {
            return "dotted".equalsIgnoreCase(value) ? DOTTED : SOLID;
        }

        @JsonValue
        public String wire() {
            return name().toLowerCase();
        }
    }
}
