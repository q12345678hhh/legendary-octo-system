package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 直接分享链接：只保存令牌哈希，解析时重新鉴权。 */
@Service
public class ShareLinkService {

    private static final Set<String> RESOURCE_TYPES = Set.of("channel", "post", "page");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CollaborationStore store;
    private final TenantTransactions transactions;
    private final ChannelService channels;

    public ShareLinkService(CollaborationStore store, TenantTransactions transactions,
                            ChannelService channels) {
        this.store = store;
        this.transactions = transactions;
        this.channels = channels;
    }

    public record CreatedShareLink(UUID id, String token, String resourceType, UUID resourceId,
                                   OffsetDateTime expiresAt) {
    }

    public record ResolvedShareLink(String resourceType, UUID resourceId, UUID channelId) {
    }

    public CreatedShareLink create(CollabViewer viewer, String resourceType, UUID resourceId,
                                   OffsetDateTime expiresAt) {
        String type = resourceType == null ? "" : resourceType.strip();
        if (!RESOURCE_TYPES.contains(type)) {
            throw ApiException.badRequest("SHARE_LINK_TYPE_INVALID", "分享链接资源类型非法");
        }
        requireResourceReadable(viewer, type, resourceId);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = sha256(token);
        return transactions.inTenant(context -> {
            store.insertShareLink(type, resourceId, hash, viewer.personId(), expiresAt);
            return new CreatedShareLink(null, token, type, resourceId, expiresAt);
        });
    }

    public ResolvedShareLink resolve(CollabViewer viewer, String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.badRequest("SHARE_LINK_TOKEN_REQUIRED", "缺少分享令牌");
        }
        CollaborationStore.ShareLink link = transactions.inTenant(context ->
                store.shareLinkByHash(sha256(token)).orElse(null));
        if (link == null || link.revokedAt() != null
                || (link.expiresAt() != null && link.expiresAt().isBefore(OffsetDateTime.now()))) {
            throw ApiException.notFound("SHARE_LINK_INVALID", "分享链接已失效");
        }
        ResolvedShareLink resolved = requireResourceReadable(viewer, link.resourceType(),
                link.resourceId());
        return resolved;
    }

    private ResolvedShareLink requireResourceReadable(CollabViewer viewer, String type,
                                                      UUID resourceId) {
        if (resourceId == null) {
            throw ApiException.badRequest("SHARE_LINK_RESOURCE_REQUIRED", "缺少资源标识");
        }
        if ("channel".equals(type)) {
            channels.requireReadable(viewer, resourceId);
            return new ResolvedShareLink(type, resourceId, resourceId);
        }
        if ("post".equals(type)) {
            CollaborationStore.Post post = transactions.inTenant(context ->
                    store.post(resourceId).orElseThrow(() ->
                            ApiException.notFound("POST_NOT_FOUND", "帖子不存在")));
            channels.requireReadable(viewer, post.channelId());
            return new ResolvedShareLink(type, resourceId, post.channelId());
        }
        CollaborationStore.Page page = transactions.inTenant(context ->
                store.page(resourceId).orElseThrow(() ->
                        ApiException.notFound("PAGE_NOT_FOUND", "知识页不存在")));
        if (!viewer.seesUnit(page.orgUnitId())) {
            throw ApiException.forbidden("PAGE_FORBIDDEN", "无权访问该知识页");
        }
        return new ResolvedShareLink(type, resourceId, null);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("当前运行环境缺少 SHA-256", cause);
        }
    }
}
