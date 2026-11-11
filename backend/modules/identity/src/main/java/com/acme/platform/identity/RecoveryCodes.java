package com.acme.platform.identity;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 一次性恢复码：10 条，格式 4-4，字母表删除易混淆字符。 */
public final class RecoveryCodes {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        List<String> codes = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            StringBuilder code = new StringBuilder(9);
            for (int j = 0; j < 8; j++) {
                if (j == 4) {
                    code.append('-');
                }
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        return codes;
    }

    public static String normalize(String code) {
        return code == null ? null : code.trim().toLowerCase(Locale.ROOT);
    }
}
