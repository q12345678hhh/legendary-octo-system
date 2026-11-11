package com.acme.platform.identity;

import com.acme.platform.platform.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 密码策略：长度、字符类别、常见弱口令、邮箱本地部分与最近 5 个历史密码。
 *
 * <p>策略只做拒绝，不做静默修正；错误信息不包含用户输入的密码。
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    public static final int HISTORY_SIZE = 5;

    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "123456", "12345678", "123456789",
            "qwerty", "qwerty123", "letmein", "admin", "admin123", "welcome",
            "iloveyou", "abc123", "111111", "000000", "changeme", "passw0rd");

    public void validate(String raw, String email, List<String> recentHashes, PasswordHasher hasher) {
        if (raw == null || raw.length() < MIN_LENGTH || raw.length() > MAX_LENGTH) {
            throw ApiException.unprocessable("PASSWORD_POLICY",
                    "密码长度必须在 " + MIN_LENGTH + " 到 " + MAX_LENGTH + " 之间");
        }
        int classes = 0;
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (char ch : raw.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                upper = true;
            } else if (Character.isLowerCase(ch)) {
                lower = true;
            } else if (Character.isDigit(ch)) {
                digit = true;
            } else {
                symbol = true;
            }
        }
        classes = (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
        if (classes < 3) {
            throw ApiException.unprocessable("PASSWORD_POLICY",
                    "密码必须包含大写、小写、数字、符号中的至少三类");
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (COMMON.contains(normalized)) {
            throw ApiException.unprocessable("PASSWORD_POLICY", "密码过于常见，请更换");
        }
        if (email != null) {
            String localPart = email.trim().toLowerCase(Locale.ROOT);
            int at = localPart.indexOf('@');
            if (at > 0) {
                localPart = localPart.substring(0, at);
            }
            if (localPart.length() >= 3 && normalized.contains(localPart)) {
                throw ApiException.unprocessable("PASSWORD_POLICY", "密码不能包含邮箱本地部分");
            }
        }
        for (String hash : recentHashes) {
            if (hasher.matches(raw, hash)) {
                throw ApiException.unprocessable("PASSWORD_REUSED",
                        "不能复用最近 " + HISTORY_SIZE + " 个密码");
            }
        }
    }
}
