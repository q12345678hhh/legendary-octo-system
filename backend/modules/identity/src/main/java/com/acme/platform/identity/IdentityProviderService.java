package com.acme.platform.identity;

import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * OIDC/SAML/SCIM 预留模型。
 *
 * <p>P0 不接真实连接器：只允许 {@code draft}/{@code disabled}；任何 {@code active}
 * 请求一律 409 {@code CONNECTOR_NOT_AVAILABLE}，避免让人误以为单点登录已经可用。
 */
@Service
public class IdentityProviderService {

    private static final Set<String> TYPES = Set.of("oidc", "saml", "scim");
    private static final Set<String> STATUSES = Set.of("draft", "disabled");

    private final TenantTransactions tenantTransactions;
    private final IdentityProviderStore store;

    public IdentityProviderService(TenantTransactions tenantTransactions, IdentityProviderStore store) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
    }

    public record CreateCommand(String providerType, String name, Map<String, Object> config,
                                String status) {
    }

    public record UpdateCommand(Map<String, Object> config, String status) {
    }

    public List<IdentityProvider> list() {
        return tenantTransactions.inTenant(ctx -> store.list(200));
    }

    public IdentityProvider create(CreateCommand command, ActorContext actor) {
        String type = command.providerType() == null ? "" : command.providerType().trim().toLowerCase();
        if (!TYPES.contains(type)) {
            throw ApiException.unprocessable("PROVIDER_TYPE_INVALID", "身份提供商类型必须是 oidc、saml 或 scim");
        }
        if (command.name() == null || command.name().isBlank() || command.name().trim().length() > 100) {
            throw ApiException.unprocessable("PROVIDER_NAME_INVALID", "名称长度必须为 1–100");
        }
        String status = command.status() == null || command.status().isBlank()
                ? "draft" : command.status().trim().toLowerCase();
        requireSupportedStatus(status);
        Map<String, Object> config = command.config() == null ? Map.of() : command.config();
        try {
            return tenantTransactions.inTenant(ctx -> {
                UUID id = UUID.randomUUID();
                store.insert(id, type, command.name().trim(), config, status, actor.actorId().toString());
                return store.find(id).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PROVIDER_EXISTS", "同类型同名身份提供商已存在");
        }
    }

    public IdentityProvider update(UUID id, UpdateCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            IdentityProvider provider = store.find(id).orElseThrow(() ->
                    ApiException.notFound("PROVIDER_NOT_FOUND", "身份提供商不存在：" + id));
            String status = command.status() == null || command.status().isBlank()
                    ? provider.status() : command.status().trim().toLowerCase();
            requireSupportedStatus(status);
            Map<String, Object> config = command.config() == null ? provider.config() : command.config();
            store.update(id, config, status);
            return store.find(id).orElseThrow();
        });
    }

    private void requireSupportedStatus(String status) {
        if ("active".equals(status)) {
            throw ApiException.conflict("CONNECTOR_NOT_AVAILABLE",
                    "P0 尚未接入单点登录连接器，身份提供商只能保持 draft 或 disabled");
        }
        if (!STATUSES.contains(status)) {
            throw ApiException.unprocessable("PROVIDER_STATUS_INVALID", "状态必须是 draft、disabled 或 active");
        }
    }
}
