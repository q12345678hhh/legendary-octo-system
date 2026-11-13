package com.acme.platform.collaboration;

import com.acme.platform.platform.TenantTransactions;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 撤权扫描：成员移除或频道归档时，立即失效旧链接、清理未读通知。
 *
 * <p>SLA 默认 5 分钟，但实现是同步完成，返回的 SLA 只用于审计与承诺展示。
 */
@Service
public class RevocationService {

    private final CollaborationStore store;
    private final TenantTransactions transactions;
    private final int slaMinutes;

    public RevocationService(CollaborationStore store, TenantTransactions transactions,
                             @Value("${collaboration.revocation-sla-minutes:5}") int slaMinutes) {
        this.store = store;
        this.transactions = transactions;
        this.slaMinutes = slaMinutes;
    }

    public record RevocationResult(boolean wasMember, int revokedShareLinks,
                                   int dismissedNotifications, int slaMinutes) {
    }

    public RevocationResult removeMember(UUID channelId, UUID personId) {
        return transactions.inTenant(context -> {
            boolean wasMember = store.activeChannelMember(channelId, personId);
            store.removeChannelMember(channelId, personId);
            int revoked = store.revokeShareLinksCreatedBy(channelId, personId);
            int dismissed = store.dismissUnreadForChannel(channelId);
            return new RevocationResult(wasMember, revoked, dismissed, slaMinutes);
        });
    }

    public RevocationResult archiveChannel(UUID channelId) {
        return transactions.inTenant(context -> {
            store.archiveChannel(channelId);
            int revoked = store.revokeShareLinksForChannel(channelId);
            int dismissed = store.dismissUnreadForChannel(channelId);
            return new RevocationResult(true, revoked, dismissed, slaMinutes);
        });
    }
}
