package com.acme.platform.authorization;

/** 数据范围类型：与数据库 {@code authorization_assignment.scope_type} 的线上枚举一致。 */
public enum ScopeType {
    TENANT,
    SELF,
    SELF_CREATED,
    DIRECT_REPORTS,
    REPORTING_CHAIN,
    ORG_UNIT,
    ORG_UNIT_SUBTREE,
    DELEGATED,
    PROJECT_SPACE,
    LEGAL_ENTITY,
    RECORD_SHARED;

    public static ScopeType fromWire(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "tenant" -> TENANT;
            case "self" -> SELF;
            case "self_created" -> SELF_CREATED;
            case "direct_reports" -> DIRECT_REPORTS;
            case "reporting_chain" -> REPORTING_CHAIN;
            case "org_unit" -> ORG_UNIT;
            case "org_unit_subtree" -> ORG_UNIT_SUBTREE;
            case "delegated" -> DELEGATED;
            case "project_space" -> PROJECT_SPACE;
            case "legal_entity" -> LEGAL_ENTITY;
            case "record_shared" -> RECORD_SHARED;
            default -> null;
        };
    }

    public String wire() {
        return name().toLowerCase();
    }

    /** tenant/self/self_created/direct_reports/reporting_chain 不需要 scope_id。 */
    public boolean requiresScopeId() {
        return switch (this) {
            case ORG_UNIT, ORG_UNIT_SUBTREE, DELEGATED, PROJECT_SPACE, LEGAL_ENTITY -> true;
            default -> false;
        };
    }
}
