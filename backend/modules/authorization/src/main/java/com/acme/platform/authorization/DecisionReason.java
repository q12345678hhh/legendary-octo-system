package com.acme.platform.authorization;

/** 判定结果原因码：稳定机器码，供前端、审计与测试断言使用。 */
public enum DecisionReason {
    ALLOWED("ALLOWED", "允许"),
    TENANT_MISSING("TENANT_MISSING", "缺少租户"),
    SUBJECT_MISSING("SUBJECT_MISSING", "缺少有效主体"),
    MEMBER_INACTIVE("MEMBER_INACTIVE", "成员不存在或已停用"),
    PERMISSION_UNKNOWN("PERMISSION_UNKNOWN", "权限码不存在"),
    NO_ALLOW("NO_ALLOW", "没有任何允许授权"),
    SCOPE_MISS("SCOPE_MISS", "数据范围不命中"),
    SCOPE_UNSUPPORTED("SCOPE_UNSUPPORTED", "范围类型尚未实现"),
    CONDITION_FAILED("CONDITION_FAILED", "条件策略不通过"),
    EXPLICIT_DENY("EXPLICIT_DENY", "命中显式拒绝"),
    SOD_CONFLICT("SOD_CONFLICT", "职责分离冲突"),
    RISK_BLOCKED("RISK_BLOCKED", "风险门禁拦截");

    private final String code;
    private final String message;

    DecisionReason(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
