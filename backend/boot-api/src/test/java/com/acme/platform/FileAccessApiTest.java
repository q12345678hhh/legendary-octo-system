package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.file.FileRecord;
import com.acme.platform.platform.file.FileService;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.tenancy.retention.RetentionService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 文件下载不是「拿到 ID 就能下」：先按 file.read 判定归属，再走原有所有者与扫描检查。 */
@WebMvcTest(controllers = FileController.class)
@Import(ProblemDetailsSupport.class)
class FileAccessApiTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();
    private static final String SUBJECT = ACTOR.toString();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private FileService fileService;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private IdentityProperties identityProperties;

    @MockitoBean
    private PlatformMetrics platformMetrics;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private RetentionService retentionService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(
                new SessionPrincipal(TENANT, UUID.randomUUID(), ACTOR, UUID.randomUUID(),
                        UUID.randomUUID(), "user", "admin@example.test", "管理员",
                        true, true, true, true));
        when(fileService.find(FILE)).thenReturn(new FileRecord(FILE, TENANT, SUBJECT,
                "report.pdf", "application/pdf", 1024, "sha256", "tenant/key", "available",
                "clean", null, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        when(fileService.authorizeDownload(FILE)).thenReturn(new FileService.DownloadGrant(FILE,
                "https://storage.example.test/presigned", OffsetDateTime.now().plusMinutes(5)));
    }

    @Test
    void downloadChecksFileReadWithOwnerBeforeIssuingUrl() throws Exception {
        mockMvc.perform(get("/api/v1/system/files/{id}/download", FILE)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://storage.example.test/presigned"));

        verify(authorization).require(eq("file.read"), eq("file"), eq(FILE),
                eq(Map.of("ownerId", SUBJECT)));
    }

    @Test
    void deniedDownloadNeverReachesStorageGrant() throws Exception {
        when(authorization.require(anyString(), anyString(), any(), any())).thenThrow(
                ApiException.forbidden("FILE_ACCESS_DENIED", "没有读取该文件的权限"));

        mockMvc.perform(get("/api/v1/system/files/{id}/download", FILE)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FILE_ACCESS_DENIED"));

        verify(fileService, never()).authorizeDownload(any());
    }
}
