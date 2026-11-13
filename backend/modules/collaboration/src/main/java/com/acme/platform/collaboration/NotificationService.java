package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 站内通知：只允许收件人本人读取与标记已读。 */
@Service
public class NotificationService {

    private final CollaborationStore store;
    private final TenantTransactions transactions;

    public NotificationService(CollaborationStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public record Inbox(List<CollaborationStore.NotificationRow> rows, int unread) {
    }

    public Inbox inbox(CollabViewer viewer, int limit) {
        if (viewer.personId() == null) {
            return new Inbox(List.of(), 0);
        }
        return transactions.inTenant(context -> new Inbox(
                store.notifications(viewer.personId(), limit),
                store.unreadNotificationCount(viewer.personId())));
    }

    public void markRead(CollabViewer viewer, UUID notificationId) {
        if (viewer.personId() == null) {
            throw ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工");
        }
        transactions.inTenant(context -> {
            store.markNotificationRead(notificationId, viewer.personId());
            return null;
        });
    }

    /**
     * 给指定员工写站内通知：供工作流任务事件消费使用。
     *
     * <p>同一收件人、资源与标题只保留一条，重复投递不会刷屏。
     */
    public void notifyPerson(UUID personId, String resourceType, UUID resourceId, String title,
                             String body) {
        if (personId == null || resourceType == null || resourceId == null || title == null) {
            return;
        }
        transactions.inTenant(context -> store.insertNotificationIfAbsent(personId, resourceType,
                resourceId, title, body == null ? "" : body));
    }
}
