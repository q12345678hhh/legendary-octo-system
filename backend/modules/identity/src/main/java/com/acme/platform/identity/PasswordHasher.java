package com.acme.platform.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 密码哈希门面：算法与强度只在装配层决定。 */
@Component
public class PasswordHasher {

    private final PasswordEncoder encoder;

    public PasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        return hash != null && encoder.matches(raw, hash);
    }
}
