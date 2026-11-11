package com.acme.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordPolicyTest {

    private final PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsStrongPassword() {
        assertThatCode(() -> policy.validate("Str0ng-Passw0rd!", "user@example.test", List.of(), hasher))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsShortOrTooLongPassword() {
        assertCode("PASSWORD_POLICY", () -> policy.validate("Ab1!short", "u@example.test", List.of(), hasher));
        assertCode("PASSWORD_POLICY",
                () -> policy.validate("Aa1!" + "x".repeat(200), "u@example.test", List.of(), hasher));
    }

    @Test
    void rejectsWhenFewerThanThreeCharacterClasses() {
        assertCode("PASSWORD_POLICY",
                () -> policy.validate("abcdefghijkl", "u@example.test", List.of(), hasher));
    }

    @Test
    void rejectsCommonPasswordEvenWhenLong() {
        assertCode("PASSWORD_POLICY",
                () -> policy.validate("Password123", "u@example.test", List.of(), hasher));
    }

    @Test
    void rejectsEmailLocalPart() {
        assertCode("PASSWORD_POLICY",
                () -> policy.validate("Alice-Str0ng-Pass", "alice@example.test", List.of(), hasher));
    }

    @Test
    void rejectsReuseOfRecentPassword() {
        String hash = hasher.hash("Old-Str0ng-Pass1");
        assertCode("PASSWORD_REUSED",
                () -> policy.validate("Old-Str0ng-Pass1", "u@example.test", List.of(hash), hasher));
    }

    private void assertCode(String expectedCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(422);
                    assertThat(error.code()).isEqualTo(expectedCode);
                });
    }
}
