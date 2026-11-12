package com.acme.platform.tenancy;

import com.acme.platform.platform.ApiException;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * 支持访问的纯规则：工单格式、理由长度、时限与职责分离。
 *
 * <p>与数据库 CHECK 重复是刻意的：数据库保证存量数据，本类保证错误以 4xx 而不是 500 返回。
 */
public final class SupportAccessRules {

    public static final int MAX_MINUTES = 60;
    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 500;

    private static final Pattern TICKET = Pattern.compile("^[A-Z][A-Z0-9-]{3,31}$");

    private SupportAccessRules() {
    }

    public static void validateRequest(String ticketId, String reason, int minutes) {
        validateRequest(ticketId, reason, minutes, MAX_MINUTES);
    }

    /** 平台可通过 {@code limits.support_access_max_minutes} 收紧上限，但绝不能突破硬上限 60。 */
    public static void validateRequest(String ticketId, String reason, int minutes, int maxMinutes) {
        int effectiveMax = Math.min(maxMinutes > 0 ? maxMinutes : MAX_MINUTES, MAX_MINUTES);
        if (ticketId == null || !TICKET.matcher(ticketId).matches()) {
            throw ApiException.badRequest("SUPPORT_TICKET_INVALID",
                    "工单号必须形如 TCK-1234：大写字母开头，可含数字与连字符，长度 4–32");
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON_LENGTH || trimmed.length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("SUPPORT_REASON_INVALID",
                    "申请理由长度必须在 " + MIN_REASON_LENGTH + "–" + MAX_REASON_LENGTH + " 个字符之间");
        }
        if (minutes < 1 || minutes > effectiveMax) {
            throw ApiException.badRequest("SUPPORT_DURATION_INVALID",
                    "支持访问时限必须在 1–" + effectiveMax + " 分钟之间");
        }
    }

    public static void validateApprover(String operatorId, String approverId) {
        if (approverId == null || approverId.isBlank()) {
            throw ApiException.badRequest("SUPPORT_APPROVER_REQUIRED", "审批人不能为空");
        }
        if (approverId.equals(operatorId)) {
            throw ApiException.forbidden("SUPPORT_SEPARATION_REQUIRED",
                    "支持访问审批人不得与申请人相同：职责分离不可绕过");
        }
    }

    public static boolean expired(TenantSupportAccess access, OffsetDateTime now) {
        return access.expiresAt() == null || !access.expiresAt().isAfter(now);
    }
}
