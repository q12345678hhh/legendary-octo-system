package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;

/** 协作文本字段的统一校验：错误码稳定，长度上限与数据库约束一致。 */
final class CollabValidation {

    private CollabValidation() {
    }

    static String requireText(String value, int maxLength, String code, String label) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest(code + "_REQUIRED", label + "不能为空");
        }
        if (text.length() > maxLength) {
            throw ApiException.badRequest(code + "_TOO_LONG",
                    label + "不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    static String optionalText(String value, int maxLength, String code, String label) {
        String text = value == null ? "" : value.strip();
        if (text.length() > maxLength) {
            throw ApiException.badRequest(code + "_TOO_LONG",
                    label + "不能超过 " + maxLength + " 个字符");
        }
        return text;
    }
}
