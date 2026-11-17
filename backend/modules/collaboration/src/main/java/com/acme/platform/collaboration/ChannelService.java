package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 频道与成员：可见性以成员表为准，`all` 频道只对部门范围开放。 */
@Service
public class ChannelService {

    private static final Set<String> SCOPES = Set.of("all", "members", "cross_department");

    private final CollaborationStore store;
    private final TenantTransactions transactions;
    private final RevocationService revocation;

    public ChannelService(CollaborationStore store, TenantTransactions transactions,
                          RevocationService revocation) {
        this.store = store;
        this.transactions = transactions;
        this.revocation = revocation;
    }

    public List<CollaborationStore.Channel> list(CollabViewer viewer, int limit) {
        return transactions.inTenant(context -> store.channels(viewer, limit));
    }

    public CollaborationStore.Channel requireReadable(CollabViewer viewer, UUID channelId) {
        return transactions.inTenant(context -> {
            CollaborationStore.Channel channel = store.channel(channelId).orElseThrow(() ->
                    ApiException.notFound("CHANNEL_NOT_FOUND", "频道不存在或已归档"));
            if (!store.channelReadable(viewer, channelId)) {
                throw ApiException.forbidden("CHANNEL_FORBIDDEN", "无权访问该频道");
            }
            return channel;
        });
    }

    public CollaborationStore.Channel create(CollabViewer viewer, UUID orgUnitId, String scope,
                                             String name, String topic) {
        if (!viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("CHANNEL_UNIT_FORBIDDEN", "只能在本部门创建频道");
        }
        String normalizedScope = scope == null || scope.isBlank() ? "members" : scope.strip();
        if (!SCOPES.contains(normalizedScope)) {
            throw ApiException.badRequest("CHANNEL_SCOPE_INVALID", "频道范围取值非法");
        }
        String safeName = CollabValidation.requireText(name, 120, "CHANNEL_NAME", "频道名称");
        String safeTopic = CollabValidation.optionalText(topic, 1000, "CHANNEL_TOPIC", "频道主题");
        return transactions.inTenant(context -> {
            UUID channelId = store.insertChannel(orgUnitId, normalizedScope, safeName, safeTopic,
                    viewer.personId());
            if (viewer.personId() != null) {
                store.upsertChannelMember(channelId, viewer.personId(), "owner");
            }
            return store.channel(channelId).orElseThrow();
        });
    }

    public List<CollaborationStore.Member> members(CollabViewer viewer, UUID channelId) {
        requireReadable(viewer, channelId);
        return transactions.inTenant(context -> store.members(channelId));
    }

    public CollaborationStore.Member addMember(CollabViewer viewer, UUID channelId,
                                               UUID personId, String role) {
        requireReadable(viewer, channelId);
        if (personId == null) {
            throw ApiException.badRequest("CHANNEL_MEMBER_REQUIRED", "缺少成员标识");
        }
        String normalizedRole = role == null || role.isBlank() ? "member" : role.strip();
        if (!"owner".equals(normalizedRole) && !"member".equals(normalizedRole)) {
            throw ApiException.badRequest("CHANNEL_MEMBER_ROLE_INVALID", "成员角色只能是 owner 或 member");
        }
        return transactions.inTenant(context -> {
            store.upsertChannelMember(channelId, personId, normalizedRole);
            return store.members(channelId).stream()
                    .filter(member -> member.personId().equals(personId))
                    .findFirst()
                    .orElseThrow();
        });
    }

    public RevocationService.RevocationResult removeMember(CollabViewer viewer, UUID channelId,
                                                           UUID personId) {
        requireReadable(viewer, channelId);
        return revocation.removeMember(channelId, personId);
    }

    public RevocationService.RevocationResult archive(CollabViewer viewer, UUID channelId) {
        requireReadable(viewer, channelId);
        return revocation.archiveChannel(channelId);
    }
}
