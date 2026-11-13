package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.collaboration.ChannelService;
import com.acme.platform.collaboration.CollabViewer;
import com.acme.platform.collaboration.MeetingService;
import com.acme.platform.collaboration.PageService;
import com.acme.platform.collaboration.PostService;
import com.acme.platform.collaboration.ProjectService;
import com.acme.platform.collaboration.RevocationService;
import com.acme.platform.collaboration.SearchService;
import com.acme.platform.collaboration.ShareLinkService;
import com.acme.platform.collaboration.TaskService;
import com.acme.platform.identity.MemberStatus;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.file.FileService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 协作 HTTP 契约：撤权后立即返回失效统计、搜索拒绝时不出数据、频道读取先鉴权。
 */
@WebMvcTest(controllers = { CollaborationController.class, SearchController.class })
@Import(ProblemDetailsSupport.class)
class CollaborationAuthzTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private ChannelService channels;

    @MockitoBean
    private PostService posts;

    @MockitoBean
    private ProjectService projects;

    @MockitoBean
    private TaskService tasks;

    @MockitoBean
    private MeetingService meetings;

    @MockitoBean
    private PageService pages;

    @MockitoBean
    private ShareLinkService shareLinks;

    @MockitoBean
    private SearchService search;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private CollabActorResolver actors;

    @MockitoBean
    private FileService files;

    @MockitoBean
    private com.acme.platform.identity.MemberStore memberStore;

    @MockitoBean
    private com.acme.platform.identity.SessionService sessionService;

    @MockitoBean
    private com.acme.platform.identity.IdentityProperties identityProperties;

    @MockitoBean
    private com.acme.platform.platform.PlatformMetrics platformMetrics;

    @MockitoBean
    private com.acme.platform.platform.idempotency.IdempotencyService idempotencyService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(
                new com.acme.platform.identity.SessionPrincipal(TENANT, UUID.randomUUID(),
                        ACTOR, USER, MEMBER, "user", "employee@example.test", "员工",
                        true, true, true, true));
        when(authorization.actor()).thenReturn(new ActorContext(TENANT, ACTOR, USER, MEMBER,
                "user", true, null));
        when(memberStore.findById(MEMBER)).thenReturn(Optional.of(new TenantMember(MEMBER, TENANT,
                USER, VIEWER, MemberStatus.ACTIVE, false, false, false)));
        when(actors.viewer(any())).thenReturn(new CollabViewer(VIEWER, false, Set.of(UUID.randomUUID())));
    }

    @Test
    void removingMemberReturnsRevocationSla() throws Exception {
        when(authorization.require(eq("collaboration.channel.manage"), eq("collaboration"),
                eq(CHANNEL), any())).thenReturn(allowed());
        when(channels.removeMember(any(), eq(CHANNEL), eq(PERSON))).thenReturn(
                new RevocationService.RevocationResult(true, 2, 3, 5));

        mockMvc.perform(delete("/api/v1/collaboration/channels/{channelId}/members/{personId}",
                        CHANNEL, PERSON)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wasMember").value(true))
                .andExpect(jsonPath("$.revokedShareLinks").value(2))
                .andExpect(jsonPath("$.dismissedNotifications").value(3))
                .andExpect(jsonPath("$.slaMinutes").value(5));
    }

    @Test
    void searchRequiresPermissionBeforeReturningAnything() throws Exception {
        when(authorization.require(eq("search.read"), eq("search"), any(), any()))
                .thenThrow(ApiException.forbidden("FORBIDDEN", "无权搜索"));

        mockMvc.perform(get("/api/v1/search?q=ab")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void channelReadChecksPermissionBeforeServiceCall() throws Exception {
        when(authorization.require(eq("collaboration.channel.read"), eq("collaboration"),
                eq(CHANNEL), any())).thenReturn(allowed());
        when(channels.requireReadable(any(), eq(CHANNEL))).thenReturn(
                new com.acme.platform.collaboration.CollaborationStore.Channel(CHANNEL,
                        UUID.randomUUID(), "members", "研发", "", VIEWER,
                        OffsetDateTime.now(), null));
        when(channels.members(any(), eq(CHANNEL))).thenReturn(List.of());
        when(posts.list(any(), eq(CHANNEL), eq(50))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/collaboration/channels/{channelId}", CHANNEL)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel.name").value("研发"))
                .andExpect(jsonPath("$.members").isArray());
    }

    private AccessDecision allowed() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee:ORG_UNIT"),
                new AccessScope(false, false, Set.of(), Set.of(UUID.randomUUID()), Set.of()),
                Map.of(), 1L, OffsetDateTime.now(), false);
    }
}
