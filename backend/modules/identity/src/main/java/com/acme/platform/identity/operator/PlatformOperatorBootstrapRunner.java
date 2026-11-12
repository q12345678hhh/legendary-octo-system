package com.acme.platform.identity.operator;

import com.acme.platform.identity.PasswordHasher;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首个平台运营账号引导：只在环境变量显式提供邮箱与密码、且表为空时创建。
 *
 * <p>幂等且 fail-closed：已存在任何运营账号就跳过；没有配置就什么都不做，
 * 绝不会在空库上自动生成弱口令账号。
 */
@Component
public class PlatformOperatorBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformOperatorBootstrapRunner.class);

    private final PlatformOperatorStore store;
    private final PasswordHasher hasher;
    private final String email;
    private final String password;
    private final String role;
    private final String displayName;

    public PlatformOperatorBootstrapRunner(
            PlatformOperatorStore store,
            PasswordHasher passwordHasher,
            @Value("${platform.ops.bootstrap-email:}") String email,
            @Value("${platform.ops.bootstrap-password:}") String password,
            @Value("${platform.ops.bootstrap-role:platform-owner}") String role,
            @Value("${platform.ops.bootstrap-display-name:平台运营}") String displayName) {
        this.store = store;
        this.hasher = passwordHasher;
        this.email = email;
        this.password = password;
        this.role = role;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (store.count() > 0) {
            log.info("已存在平台运营账号，跳过引导创建");
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("@")) {
            log.warn("平台运营引导邮箱格式不正确，跳过");
            return;
        }
        if (password.length() < 12) {
            log.warn("平台运营引导密码少于 12 位，拒绝创建");
            return;
        }
        if (!PlatformPermissionCatalog.validRole(role)) {
            log.warn("平台运营引导角色不合法（{}），拒绝创建", role);
            return;
        }
        store.insert(normalized, displayName, role, hasher.hash(password));
        log.info("已创建首个平台运营账号 {}，角色 {}", normalized, role);
    }
}
