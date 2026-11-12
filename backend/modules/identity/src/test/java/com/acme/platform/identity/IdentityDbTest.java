package com.acme.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.organization.DirectoryScope;
import com.acme.platform.organization.Employment;
import com.acme.platform.organization.EmploymentStatus;
import com.acme.platform.organization.EmploymentStore;
import com.acme.platform.organization.PeopleService;
import com.acme.platform.organization.Person;
import com.acme.platform.organization.PersonStore;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.tenancy.SupportAccessStore;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL 上的身份与会话闭环。
 *
 * <p>覆盖验收要求：无账号员工、一人多雇佣、再雇佣、账号更换、邀请一次性、
 * 过期/撤销/代际失效与跨租户令牌拒绝。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class IdentityDbTest {

    private static final String PASSWORD = "Str0ng-Passw0rd!";

    private TenantTransactions tenantTransactions;

    @Test
    void identityLifecycleAcrossScenarios() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantTransactions tenantTransactions = new TenantTransactions(jdbc, transactionTemplate);
        this.tenantTransactions = tenantTransactions;
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        TenantStore tenantStore = new TenantStore(jdbc, mapper);
        TenantLifecycleService lifecycle = new TenantLifecycleService(tenantTransactions, tenantStore,
                new SupportAccessStore(jdbc), clock, 30);

        UserAccountStore accounts = new UserAccountStore(jdbc);
        ActorStore actors = new ActorStore(jdbc);
        MemberStore members = new MemberStore(jdbc);
        SessionStore sessions = new SessionStore(jdbc);
        MfaStore mfaStore = new MfaStore(jdbc);
        InvitationStore invitations = new InvitationStore(jdbc);
        ApiTokenStore apiTokens = new ApiTokenStore(jdbc);
        ServiceAccountStore serviceAccounts = new ServiceAccountStore(jdbc);
        AuthEventStore authEvents = new AuthEventStore(jdbc);
        PersonStore people = new PersonStore(jdbc);
        EmploymentStore employments = new EmploymentStore(jdbc);

        IdentityProperties properties = new IdentityProperties();
        properties.setMfaKey(Base64.getEncoder().encodeToString(new byte[32]));
        PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
        PasswordPolicy policy = new PasswordPolicy();
        SessionService sessionService = new SessionService(tenantTransactions, sessions, apiTokens,
                accounts, members, actors, serviceAccounts, properties, clock);
        MfaService mfaService = new MfaService(tenantTransactions, mfaStore, accounts,
                new TotpSecretCipher(properties), clock);
        InvitationService invitationService = new InvitationService(tenantTransactions, accounts,
                actors, members, invitations, people, hasher, policy, sessionService, authEvents,
                properties, clock);
        AuthenticationService authentication = new AuthenticationService(tenantTransactions, accounts,
                members, actors, mfaStore, mfaService, sessionService, hasher, authEvents, properties,
                clock);
        AccountService accountService = new AccountService(tenantTransactions, accounts, actors,
                members, hasher, policy, sessionService, authEvents);
        PeopleService peopleService = new PeopleService(tenantTransactions, people, employments);
        ServiceAccountService serviceAccountService = new ServiceAccountService(tenantTransactions,
                serviceAccounts, apiTokens, actors, sessionService, authEvents, clock);

        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantA, "id-a-" + suffix,
                "身份甲", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "id-a-" + suffix + ".example.test", List.of(), null), "ops");
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantB, "id-b-" + suffix,
                "身份乙", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "id-b-" + suffix + ".example.test", List.of(), null), "ops");
        lifecycle.initialize(tenantA, "ops");
        lifecycle.initialize(tenantB, "ops");

        // 1. 无账号员工：只有 Person + Employment，没有登录账号
        Person person = withContext(tenantA, () -> peopleService.create(new PeopleService.CreatePersonCommand(
                "张伟", "zhang@" + suffix + ".test", "EMP-001", LocalDate.parse("2024-01-01"), "工程师")));
        assertThat(withContext(tenantA, () -> accounts.findByEmailOnly(
                "zhang@" + suffix + ".test"))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity_user_account", Long.class)).isZero();

        // 2. 邀请：首个成员自动 owner + admin + 强制 MFA，令牌只存哈希
        InvitationService.InvitationIssued invitation = withContext(tenantA, () ->
                invitationService.create(new InvitationService.CreateInvitationCommand(
                        "zhang@" + suffix + ".test", "张伟", person.id(), null, null)));
        assertThat(invitation.owner()).isTrue();
        assertThat(invitation.admin()).isTrue();
        assertThat(invitation.mfaRequired()).isTrue();
        assertThat(invitation.activationToken()).startsWith("plt_inv_");
        String storedHash = withContext(tenantA, () -> jdbc.queryForObject(
                "SELECT token_hash FROM identity_invitation WHERE user_id = ?",
                String.class, invitation.userId()));
        assertThat(storedHash).isNotEqualTo(invitation.activationToken())
                .isEqualTo(TokenHasher.hash(invitation.activationToken()));

        // 3. 激活并拿到会话
        InvitationService.AcceptedInvitation accepted = invitationService.accept(tenantA,
                new InvitationService.AcceptInvitationCommand(invitation.activationToken(), PASSWORD),
                device());
        assertThat(accepted.principal().owner()).isTrue();
        assertThat(accepted.principal().mfaRequired()).isTrue();
        assertThat(accepted.principal().mfaSatisfied()).isFalse();
        assertThat(sessionService.authenticate(tenantA, accepted.sessionToken()).userId())
                .isEqualTo(invitation.userId());

        // 4. 邀请令牌一次性
        assertThatThrownBy(() -> invitationService.accept(tenantA,
                new InvitationService.AcceptInvitationCommand(invitation.activationToken(), PASSWORD),
                device()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("INVITATION_ALREADY_ACCEPTED"));

        // 5. 密码登录可用，但强制 MFA 的成员会话未完成第二因子
        AuthenticationService.LoginResult login = authentication.login(tenantA,
                new AuthenticationService.LoginCommand("zhang@" + suffix + ".test", PASSWORD), device());
        assertThat(login.status()).isEqualTo("session");
        assertThat(login.principal().mfaSatisfied()).isFalse();

        // 6. 撤销后立即失效
        withContext(tenantA, () -> {
            sessionService.revokeOwned(login.principal().sessionId(), invitation.userId(), "actor", "test");
            return null;
        });
        assertThatThrownBy(() -> sessionService.authenticate(tenantA, login.sessionToken()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("SESSION_REVOKED"));

        // 7. 过期会话被拒绝并顺带撤销
        AuthenticationService.LoginResult second = authentication.login(tenantA,
                new AuthenticationService.LoginCommand("zhang@" + suffix + ".test", PASSWORD), device());
        withContext(tenantA, () -> {
            jdbc.update("""
                    UPDATE identity_session
                       SET created_at = now() - interval '2 hours',
                           idle_expires_at = now() - interval '1 hour',
                           expires_at = now() - interval '1 minute'
                     WHERE id = ?
                    """, second.principal().sessionId());
            return null;
        });
        assertThatThrownBy(() -> sessionService.authenticate(tenantA, second.sessionToken()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("SESSION_EXPIRED"));

        // 8. auth_version 变化后旧会话失效
        AuthenticationService.LoginResult third = authentication.login(tenantA,
                new AuthenticationService.LoginCommand("zhang@" + suffix + ".test", PASSWORD), device());
        withContext(tenantA, () -> {
            accounts.bumpAuthVersion(invitation.userId());
            return null;
        });
        assertThatThrownBy(() -> sessionService.authenticate(tenantA, third.sessionToken()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("SESSION_INVALID"));

        // 9. 跨租户令牌被 RLS 直接拒绝
        AuthenticationService.LoginResult fourth = authentication.login(tenantA,
                new AuthenticationService.LoginCommand("zhang@" + suffix + ".test", PASSWORD), device());
        assertThatThrownBy(() -> sessionService.authenticate(tenantB, fourth.sessionToken()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("SESSION_INVALID"));

        // 10. 一人多雇佣与再雇佣
        Employment secondEmployment = withContext(tenantA, () ->
                peopleService.addEmployment(person.id(), new PeopleService.AddEmploymentCommand(
                        "EMP-002", LocalDate.parse("2024-06-01"), "兼职顾问")));
        Employment firstEmployment = withContext(tenantA, () ->
                        peopleService.employments(person.id(), DirectoryScope.tenant(person.id(), false)))
                .stream().filter(item -> "EMP-001".equals(item.employmentNo())).findFirst().orElseThrow();
        withContext(tenantA, () -> peopleService.endEmployment(firstEmployment.id(),
                LocalDate.parse("2024-05-31")));
        Employment rehire = withContext(tenantA, () ->
                peopleService.addEmployment(person.id(), new PeopleService.AddEmploymentCommand(
                        "EMP-003", LocalDate.parse("2025-01-01"), "高级工程师")));
        List<Employment> history = withContext(tenantA, () ->
                peopleService.employments(person.id(), DirectoryScope.tenant(person.id(), false)));
        assertThat(history).hasSize(3);
        assertThat(history.stream().filter(item -> item.status() == EmploymentStatus.ACTIVE))
                .extracting(Employment::employmentNo).containsExactlyInAnyOrder("EMP-002", "EMP-003");
        assertThat(secondEmployment.status()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(rehire.effectiveFrom()).isAfter(firstEmployment.effectiveFrom());

        // 11. 账号更换：成员保留，旧账号停用，历史留痕
        UUID newUserId = UUID.randomUUID();
        withContext(tenantA, () -> {
            accounts.insertInvited(newUserId, "li@" + suffix + ".test", "li@" + suffix + ".test", "李四");
            accounts.activate(newUserId, hasher.hash(PASSWORD));
            accounts.insertPasswordHistory(newUserId, hasher.hash(PASSWORD));
            actors.insert(UUID.randomUUID(), ActorType.USER, "李四", newUserId);
            return null;
        });
        AccountService.MemberView replaced = withContext(tenantA, () -> accountService.changeAccount(
                accepted.memberId(), new AccountService.ChangeAccountCommand(newUserId, "张伟离职，岗位由李四接替"),
                actor(tenantA)));
        assertThat(replaced.userId()).isEqualTo(newUserId);
        assertThat(withContext(tenantA, () -> accounts.findAccount(invitation.userId()))
                .orElseThrow().status()).isEqualTo(UserStatus.DISABLED);
        assertThat(withContext(tenantA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM identity_member_account_history WHERE member_id = ?",
                Long.class, accepted.memberId()))).isEqualTo(1);
        // 账号更换会显式撤销旧账号的全部会话
        assertThatThrownBy(() -> sessionService.authenticate(tenantA, fourth.sessionToken()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("SESSION_REVOKED"));

        // 12. 服务账号令牌：可用、跨租户拒绝、撤销生效
        ServiceAccount serviceAccount = withContext(tenantA, () ->
                serviceAccountService.create(new ServiceAccountService.CreateServiceAccountCommand(
                        "构建机器人", "CI 只读"), actor(tenantA)));
        ServiceAccountService.IssuedTokenView token = withContext(tenantA, () ->
                serviceAccountService.createToken(serviceAccount.id(),
                        new ServiceAccountService.CreateTokenCommand("CI", List.of("people:read"), 30),
                        actor(tenantA)));
        assertThat(token.token()).startsWith("plt_api_");
        assertThat(sessionService.authenticateToken(tenantA, token.token()).actorType()).isEqualTo("service");
        assertThatThrownBy(() -> sessionService.authenticateToken(tenantB, token.token()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("TOKEN_INVALID"));
        withContext(tenantA, () -> {
            serviceAccountService.revokeToken(serviceAccount.id(), token.id(), actor(tenantA));
            return null;
        });
        assertThatThrownBy(() -> sessionService.authenticateToken(tenantA, token.token()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("TOKEN_REVOKED"));

        // 13. MFA：注册、确认、登录挑战、高权限成员禁止关闭
        MfaService.Enrollment enrollment = withContext(tenantA, () ->
                mfaService.start(newUserId, "li@" + suffix + ".test"));
        long epoch = clock.instant().getEpochSecond();
        List<String> recoveryCodes = withContext(tenantA, () ->
                mfaService.confirm(newUserId, Totp.currentCode(enrollment.secret(), epoch)));
        assertThat(recoveryCodes).hasSize(10);
        AuthenticationService.LoginResult challenge = authentication.login(tenantA,
                new AuthenticationService.LoginCommand("li@" + suffix + ".test", PASSWORD), device());
        assertThat(challenge.status()).isEqualTo("mfa_required");
        AuthenticationService.LoginResult verified = withContext(tenantA, () ->
                authentication.verifyMfa(tenantA,
                        new AuthenticationService.MfaVerifyCommand(challenge.mfaToken(),
                                Totp.currentCode(enrollment.secret(), epoch + 30)), device()));
        assertThat(verified.principal().mfaSatisfied()).isTrue();
        assertThatThrownBy(() -> withContext(tenantA, () -> {
            mfaService.disable(newUserId);
            return null;
        }))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("MFA_REQUIRED_FOR_PRIVILEGED"));

        // 14. 身份提供商只能停留在 draft/disabled
        IdentityProviderService providers = new IdentityProviderService(tenantTransactions,
                new IdentityProviderStore(jdbc, mapper));
        IdentityProvider draft = withContext(tenantA, () -> providers.create(
                new IdentityProviderService.CreateCommand("oidc", "企业单点登录",
                        java.util.Map.of("issuer", "https://idp.example.test"), "draft"),
                actor(tenantA)));
        assertThat(draft.status()).isEqualTo("draft");
        assertThatThrownBy(() -> withContext(tenantA, () -> providers.update(draft.id(),
                new IdentityProviderService.UpdateCommand(java.util.Map.of(), "active"))))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("CONNECTOR_NOT_AVAILABLE"));

        // 15. 无上下文时 16 张身份表全部不可见
        for (String table : List.of("organization_person", "organization_employment",
                "identity_user_account", "identity_actor", "identity_service_account",
                "identity_tenant_member", "identity_member_account_history",
                "identity_password_history", "identity_invitation", "identity_mfa_method",
                "identity_recovery_code", "identity_mfa_challenge", "identity_session",
                "identity_api_token", "identity_identity_provider", "identity_auth_event")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
                    .as(table).isZero();
        }
    }

    private SessionService.DeviceInfo device() {
        return new SessionService.DeviceInfo(null, "junit", "127.0.0.1", "JUnit");
    }

    private com.acme.platform.platform.ActorContext actor(UUID tenantId) {
        return new com.acme.platform.platform.ActorContext(tenantId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "user", true, UUID.randomUUID());
    }

    /**
     * 所有数据库访问都必须落在租户事务内：RLS 的 {@code app.tenant_id} 是事务级设置，
     * 裸连接上的查询看不到任何租户行。
     */
    private <T> T withContext(UUID tenantId, java.util.function.Supplier<T> action) {
        RequestContext context = new RequestContext(tenantId, null, UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", ""));
        RequestContextHolder.set(context);
        try {
            return tenantTransactions.inTenant(context, ctx -> action.get());
        } finally {
            RequestContextHolder.clear();
            ActorContextHolder.clear();
        }
    }

    private void withContext(UUID tenantId, Runnable action) {
        withContext(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
