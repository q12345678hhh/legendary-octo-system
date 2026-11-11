package com.acme.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-11T08:00:00Z");

    private TenantTransactions tenantTransactions;
    private SessionStore sessionStore;
    private ApiTokenStore apiTokenStore;
    private UserAccountStore userAccountStore;
    private MemberStore memberStore;
    private ActorStore actorStore;
    private ServiceAccountStore serviceAccountStore;
    private SessionService sessionService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tenantTransactions = mock(TenantTransactions.class);
        when(tenantTransactions.inTenant(any(RequestContext.class), any(Function.class)))
                .thenAnswer(invocation -> ((Function<RequestContext, Object>) invocation.getArgument(1))
                        .apply(invocation.getArgument(0)));
        sessionStore = mock(SessionStore.class);
        apiTokenStore = mock(ApiTokenStore.class);
        userAccountStore = mock(UserAccountStore.class);
        memberStore = mock(MemberStore.class);
        actorStore = mock(ActorStore.class);
        serviceAccountStore = mock(ServiceAccountStore.class);
        sessionService = new SessionService(tenantTransactions, sessionStore, apiTokenStore,
                userAccountStore, memberStore, actorStore, serviceAccountStore,
                new IdentityProperties(), Clock.fixed(now.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void rejectsExpiredSessionAndRevokesIt() {
        SessionRecord expired = session(now.minusSeconds(60), now.minusSeconds(30), 1, null);
        when(sessionStore.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> sessionService.authenticate(tenantId, "plt_s_token"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SESSION_EXPIRED"));
        verify(sessionStore).revoke(expired.id(), "expired", "system");
    }

    @Test
    void rejectsSessionWhenAuthVersionChanged() {
        when(sessionStore.findByTokenHash(any())).thenReturn(Optional.of(
                session(now.minusSeconds(10), now.plusSeconds(3600), 1, null)));
        when(userAccountStore.findAccount(userId)).thenReturn(Optional.of(account(UserStatus.ACTIVE, 2)));

        assertThatThrownBy(() -> sessionService.authenticate(tenantId, "plt_s_token"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SESSION_INVALID"));
    }

    @Test
    void rejectsSessionWhenMemberIsDisabled() {
        when(sessionStore.findByTokenHash(any())).thenReturn(Optional.of(
                session(now.minusSeconds(10), now.plusSeconds(3600), 1, null)));
        when(userAccountStore.findAccount(userId)).thenReturn(Optional.of(account(UserStatus.ACTIVE, 1)));
        when(memberStore.findByUserId(userId)).thenReturn(Optional.of(member(MemberStatus.DISABLED)));
        when(actorStore.findById(actorId)).thenReturn(Optional.of(actor("active")));

        assertThatThrownBy(() -> sessionService.authenticate(tenantId, "plt_s_token"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MEMBER_INACTIVE"));
    }

    @Test
    void rejectsRevokedSession() {
        when(sessionStore.findByTokenHash(any())).thenReturn(Optional.of(
                session(now.minusSeconds(10), now.plusSeconds(3600), 1, now.minusSeconds(10))));

        assertThatThrownBy(() -> sessionService.authenticate(tenantId, "plt_s_token"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SESSION_REVOKED"));
    }

    @Test
    void unknownTokenIsRejected() {
        when(sessionStore.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.authenticate(tenantId, "plt_s_token"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SESSION_INVALID"));
    }

    @Test
    void refreshesIdleExpiryWhenLastSeenOverMinute() {
        SessionRecord stale = session(now.minusSeconds(120), now.plusSeconds(3600), 1, null);
        when(sessionStore.findByTokenHash(any())).thenReturn(Optional.of(stale));
        when(userAccountStore.findAccount(userId)).thenReturn(Optional.of(account(UserStatus.ACTIVE, 1)));
        when(memberStore.findByUserId(userId)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
        when(actorStore.findById(actorId)).thenReturn(Optional.of(actor("active")));

        SessionPrincipal principal = sessionService.authenticate(tenantId, "plt_s_token");

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.memberId()).isEqualTo(memberId);
        verify(sessionStore).touch(any(), any(), any());
    }

    private SessionRecord session(OffsetDateTime lastSeenAt, OffsetDateTime expiresAt, int authVersion,
                                  OffsetDateTime revokedAt) {
        return new SessionRecord(UUID.randomUUID(), tenantId, userId, actorId, TokenHasher.hash("t"),
                authVersion, true, null, null, "127.0.0.1", "junit",
                lastSeenAt.minusSeconds(120), lastSeenAt, lastSeenAt.plusSeconds(1800), expiresAt,
                revokedAt);
    }

    private UserAccount account(UserStatus status, int authVersion) {
        return new UserAccount(userId, tenantId, "user@example.test", "用户", status, authVersion,
                true, 0, null, now, null);
    }

    private TenantMember member(MemberStatus status) {
        return new TenantMember(memberId, tenantId, userId, null, status, true, true, true);
    }

    private Actor actor(String status) {
        return new Actor(actorId, tenantId, ActorType.USER, "用户", status, userId);
    }
}
