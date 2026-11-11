package com.acme.platform.identity;

import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 服务账号与 API 令牌。
 *
 * <p>服务账号只能持 Bearer 令牌调用 API，不能登录；令牌明文只在创建响应出现一次，
 * 最长 90 天，撤销立即生效。
 */
@Service
public class ServiceAccountService {

    static final int MAX_TOKEN_DAYS = 90;

    private final TenantTransactions tenantTransactions;
    private final ServiceAccountStore serviceAccountStore;
    private final ApiTokenStore apiTokenStore;
    private final ActorStore actorStore;
    private final SessionService sessionService;
    private final AuthEventStore authEventStore;
    private final Clock clock;

    public ServiceAccountService(TenantTransactions tenantTransactions,
                                 ServiceAccountStore serviceAccountStore,
                                 ApiTokenStore apiTokenStore,
                                 ActorStore actorStore,
                                 SessionService sessionService,
                                 AuthEventStore authEventStore,
                                 Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.serviceAccountStore = serviceAccountStore;
        this.apiTokenStore = apiTokenStore;
        this.actorStore = actorStore;
        this.sessionService = sessionService;
        this.authEventStore = authEventStore;
        this.clock = clock;
    }

    public record CreateServiceAccountCommand(String name, String description) {
    }

    public record CreateTokenCommand(String name, List<String> scopes, Integer ttlDays) {
    }

    public record IssuedTokenView(UUID id, String name, List<String> scopes,
                                  OffsetDateTime expiresAt, String token) {
    }

    public record TokenView(UUID id, String name, String prefix, List<String> scopes,
                            OffsetDateTime expiresAt, OffsetDateTime revokedAt,
                            OffsetDateTime lastUsedAt, OffsetDateTime createdAt) {
    }

    public List<ServiceAccount> list() {
        return tenantTransactions.inTenant(ctx -> serviceAccountStore.list(200));
    }

    public ServiceAccount create(CreateServiceAccountCommand command, ActorContext actor) {
        if (command.name() == null || command.name().isBlank() || command.name().trim().length() > 100) {
            throw ApiException.unprocessable("SERVICE_ACCOUNT_NAME_INVALID", "服务账号名称长度必须为 1–100");
        }
        try {
            return tenantTransactions.inTenant(ctx -> {
                UUID id = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();
                String name = command.name().trim();
                actorStore.insert(actorId, ActorType.SERVICE, name, null);
                serviceAccountStore.insert(id, actorId, name, command.description(),
                        actor.actorId().toString());
                return serviceAccountStore.find(id).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SERVICE_ACCOUNT_EXISTS", "同名服务账号已存在");
        }
    }

    public void disable(UUID id, ActorContext actor) {
        tenantTransactions.inTenant(ctx -> {
            ServiceAccount account = serviceAccountStore.find(id).orElseThrow(() ->
                    ApiException.notFound("SERVICE_ACCOUNT_NOT_FOUND", "服务账号不存在：" + id));
            serviceAccountStore.disable(id);
            actorStore.disable(account.actorId());
            sessionService.revokeAllForService(account.id(), actor.actorId().toString());
            return null;
        });
    }

    public IssuedTokenView createToken(UUID serviceAccountId, CreateTokenCommand command,
                                       ActorContext actor) {
        int ttlDays = command.ttlDays() == null ? 30 : command.ttlDays();
        if (ttlDays < 1 || ttlDays > MAX_TOKEN_DAYS) {
            throw ApiException.unprocessable("TOKEN_TTL_INVALID",
                    "令牌有效期必须为 1–" + MAX_TOKEN_DAYS + " 天");
        }
        if (command.name() == null || command.name().isBlank() || command.name().trim().length() > 100) {
            throw ApiException.unprocessable("TOKEN_NAME_INVALID", "令牌名称长度必须为 1–100");
        }
        List<String> scopes = command.scopes() == null ? List.of() : command.scopes().stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return tenantTransactions.inTenant(ctx -> {
            ServiceAccount account = serviceAccountStore.find(serviceAccountId).orElseThrow(() ->
                    ApiException.notFound("SERVICE_ACCOUNT_NOT_FOUND",
                            "服务账号不存在：" + serviceAccountId));
            if (!"active".equals(account.status())) {
                throw ApiException.conflict("SERVICE_ACCOUNT_DISABLED", "服务账号已停用");
            }
            TokenHasher.IssuedToken token = TokenHasher.issue("api");
            UUID tokenId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(Duration.ofDays(ttlDays));
            apiTokenStore.insert(tokenId, account.id(), account.actorId(), command.name().trim(),
                    token.hash(), token.prefix(), scopes, expiresAt, actor.actorId().toString());
            authEventStore.record("token_create", true, null, actor.actorId(), null, null);
            return new IssuedTokenView(tokenId, command.name().trim(), scopes, expiresAt, token.raw());
        });
    }

    public List<TokenView> listTokens(UUID serviceAccountId) {
        return tenantTransactions.inTenant(ctx -> apiTokenStore.listByAccount(serviceAccountId).stream()
                .map(token -> new TokenView(token.id(), token.name(), token.prefix(), token.scopes(),
                        token.expiresAt(), token.revokedAt(), token.lastUsedAt(), token.createdAt()))
                .toList());
    }

    public void revokeToken(UUID serviceAccountId, UUID tokenId, ActorContext actor) {
        tenantTransactions.inTenant(ctx -> {
            ApiToken token = apiTokenStore.find(tokenId).orElseThrow(() ->
                    ApiException.notFound("TOKEN_NOT_FOUND", "令牌不存在：" + tokenId));
            if (!token.serviceAccountId().equals(serviceAccountId)) {
                throw ApiException.notFound("TOKEN_NOT_FOUND", "令牌不属于该服务账号");
            }
            apiTokenStore.revoke(tokenId, actor.actorId().toString());
            authEventStore.record("token_revoke", true, null, actor.actorId(), null, null);
            return null;
        });
    }
}
