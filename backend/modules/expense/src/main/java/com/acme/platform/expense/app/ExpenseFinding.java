package com.acme.platform.expense.app;

import java.math.BigDecimal;

/**
 * 单条规则结果：规则码、结论、可读说明与实际/期望值。
 *
 * @param code       规则码，例如 R001
 * @param status     pass / fail / blocked / skipped
 * @param message    面向员工与复核人的说明
 * @param actual     实际值
 * @param expected   期望值或规则要求
 * @param confidence 相关字段的最低置信度；无置信度语义时为 null
 */
public record ExpenseFinding(
        String code,
        String status,
        String message,
        String actual,
        String expected,
        BigDecimal confidence) {

    public static final String PASS = "pass";
    public static final String FAIL = "fail";
    public static final String BLOCKED = "blocked";
    public static final String SKIPPED = "skipped";

    public static ExpenseFinding pass(String code, String message) {
        return new ExpenseFinding(code, PASS, message, null, null, null);
    }

    public static ExpenseFinding skip(String code, String message) {
        return new ExpenseFinding(code, SKIPPED, message, null, null, null);
    }

    public static ExpenseFinding fail(String code, String message, String actual, String expected) {
        return new ExpenseFinding(code, FAIL, message, actual, expected, null);
    }

    public static ExpenseFinding blocked(String code, String message) {
        return new ExpenseFinding(code, BLOCKED, message, null, null, null);
    }

    public boolean blocking() {
        return FAIL.equals(status) || BLOCKED.equals(status);
    }
}
