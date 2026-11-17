package com.acme.platform.tenancy.retention;

import java.util.Optional;

/**
 * 有法定保存义务、不允许普通删除的数据类。
 */
public enum ProtectedDataClass {

    AUDIT("audit", "审计记录按合规要求只读保留（默认 7 年），不允许普通删除"),
    PAYROLL_RESULT("payroll_result", "工资结果有法定保存义务，不允许普通删除");

    private final String wireName;
    private final String retentionReason;

    ProtectedDataClass(String wireName, String retentionReason) {
        this.wireName = wireName;
        this.retentionReason = retentionReason;
    }

    public String wireName() {
        return wireName;
    }

    public String retentionReason() {
        return retentionReason;
    }

    public static Optional<ProtectedDataClass> of(String dataClass) {
        for (ProtectedDataClass candidate : values()) {
            if (candidate.wireName.equals(dataClass)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
