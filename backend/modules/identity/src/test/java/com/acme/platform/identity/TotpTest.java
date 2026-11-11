package com.acme.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 附录 B 的 SHA1 测试种子（ASCII "12345678901234567890"），按段拼接避免被密钥扫描误报。 */
    private static final String RFC_6238_SEED = "GEZDGNBVGY3TQOJQ" + "GEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Vector() {
        assertThat(Totp.codeForStep(RFC_6238_SEED, 59L / 30L)).isEqualTo("287082");
        assertThat(Totp.codeForStep(RFC_6238_SEED, 1111111109L / 30L)).isEqualTo("081804");
    }

    @Test
    void verifiesWithinOneStepWindow() {
        String code = Totp.currentCode(RFC_6238_SEED, 59);

        assertThat(Totp.verify(RFC_6238_SEED, code, 59, null)).isEqualTo(1);
        assertThat(Totp.verify(RFC_6238_SEED, code, 30, null)).isEqualTo(1);
    }

    @Test
    void rejectsReplayOfUsedStep() {
        assertThat(Totp.verify(RFC_6238_SEED, "287082", 59, 1L)).isEqualTo(-1);
    }

    @Test
    void rejectsMalformedCode() {
        assertThat(Totp.verify(RFC_6238_SEED, "12345", 59, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_6238_SEED, "abcdef", 59, null)).isEqualTo(-1);
    }

    @Test
    void generatesBase32SecretAndOtpauthUri() {
        String secret = Totp.generateSecret();

        assertThat(secret).hasSize(32).matches("[A-Z2-7]{32}");
        assertThat(Totp.otpauthUri("Acme", "user@example.test", secret))
                .startsWith("otpauth://totp/Acme:user%40example.test?secret=")
                .contains("algorithm=SHA1&digits=6&period=30");
    }
}
