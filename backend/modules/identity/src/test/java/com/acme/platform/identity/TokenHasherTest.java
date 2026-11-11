package com.acme.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHasherTest {

    @Test
    void issuesOpaqueTokenWithStableHash() {
        TokenHasher.IssuedToken token = TokenHasher.issue("s");

        assertThat(token.raw()).startsWith("plt_s_");
        assertThat(token.hash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(token.hash()).isEqualTo(TokenHasher.hash(token.raw()));
        assertThat(token.prefix()).startsWith("s_s_");
    }

    @Test
    void issuesDifferentTokensEveryTime() {
        assertThat(TokenHasher.issue("api").raw()).isNotEqualTo(TokenHasher.issue("api").raw());
    }

    @Test
    void constantTimeEqualsRejectsNullAndMismatch() {
        assertThat(TokenHasher.constantTimeEquals("a", "a")).isTrue();
        assertThat(TokenHasher.constantTimeEquals("a", "b")).isFalse();
        assertThat(TokenHasher.constantTimeEquals(null, "a")).isFalse();
    }
}
