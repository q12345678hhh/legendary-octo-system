package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 会议纪要、行动项与决定记录。 */
@Service
public class MeetingService {

    private final CollaborationStore store;
    private final TenantTransactions transactions;
    private final ProjectService projects;

    public MeetingService(CollaborationStore store, TenantTransactions transactions,
                          ProjectService projects) {
        this.store = store;
        this.transactions = transactions;
        this.projects = projects;
    }

    public record MeetingDetail(CollaborationStore.Meeting meeting,
                                List<CollaborationStore.ActionItem> actionItems) {
    }

    public List<CollaborationStore.Meeting> list(CollabViewer viewer, UUID orgUnitId, int limit) {
        if (orgUnitId != null && !viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("MEETING_UNIT_FORBIDDEN", "无权访问该部门会议");
        }
        return transactions.inTenant(context -> store.meetings(viewer, orgUnitId, limit));
    }

    public MeetingDetail detail(CollabViewer viewer, UUID meetingId) {
        CollaborationStore.Meeting meeting = requireReadable(viewer, meetingId);
        return transactions.inTenant(context -> new MeetingDetail(meeting,
                store.actionItems(meetingId)));
    }

    public CollaborationStore.Meeting create(CollabViewer viewer, UUID orgUnitId, UUID projectId,
                                             String title, LocalDate meetingDate, String notes) {
        if (!viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("MEETING_UNIT_FORBIDDEN", "只能在本部门创建会议");
        }
        if (projectId != null) {
            projects.requireReadable(viewer, projectId);
        }
        String safeTitle = CollabValidation.requireText(title, 200, "MEETING_TITLE", "会议标题");
        String safeNotes = CollabValidation.optionalText(notes, 20000, "MEETING_NOTES", "会议纪要");
        return transactions.inTenant(context -> {
            UUID id = store.insertMeeting(orgUnitId, projectId, safeTitle, meetingDate, safeNotes,
                    viewer.personId());
            return store.meeting(id).orElseThrow();
        });
    }

    public CollaborationStore.ActionItem addActionItem(CollabViewer viewer, UUID meetingId,
                                                       String title, UUID ownerPersonId,
                                                       LocalDate dueDate) {
        requireReadable(viewer, meetingId);
        if (ownerPersonId == null) {
            throw ApiException.badRequest("ACTION_ITEM_OWNER_REQUIRED", "缺少行动项负责人");
        }
        String safeTitle = CollabValidation.requireText(title, 200, "ACTION_ITEM_TITLE", "行动项标题");
        return transactions.inTenant(context -> {
            UUID id = store.insertActionItem(meetingId, safeTitle, ownerPersonId, dueDate);
            return store.actionItem(id).orElseThrow();
        });
    }

    public CollaborationStore.ActionItem completeActionItem(CollabViewer viewer,
                                                             UUID actionItemId) {
        return transactions.inTenant(context -> {
            CollaborationStore.ActionItem item = store.actionItem(actionItemId).orElseThrow(() ->
                    ApiException.notFound("ACTION_ITEM_NOT_FOUND", "行动项不存在"));
            if (!viewer.tenantWide() && (viewer.personId() == null
                    || !viewer.personId().equals(item.ownerPersonId()))) {
                throw ApiException.forbidden("ACTION_ITEM_NOT_OWNER", "只能完成自己的行动项");
            }
            store.completeActionItem(actionItemId);
            return store.actionItem(actionItemId).orElseThrow();
        });
    }

    public List<CollaborationStore.ActionItem> myActionItems(CollabViewer viewer, int limit) {
        if (viewer.personId() == null) {
            return List.of();
        }
        return transactions.inTenant(context ->
                store.actionItemsForOwner(viewer.personId(), limit));
    }

    public List<CollaborationStore.Decision> decisions(CollabViewer viewer, UUID orgUnitId,
                                                        int limit) {
        if (orgUnitId != null && !viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("DECISION_UNIT_FORBIDDEN", "无权访问该部门决定");
        }
        return transactions.inTenant(context -> store.decisions(viewer, orgUnitId, limit));
    }

    public CollaborationStore.Decision createDecision(CollabViewer viewer, UUID orgUnitId,
                                                      UUID meetingId, String title, String body,
                                                      LocalDate decidedAt) {
        if (!viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("DECISION_UNIT_FORBIDDEN", "只能在本部门记录决定");
        }
        if (meetingId != null) {
            requireReadable(viewer, meetingId);
        }
        String safeTitle = CollabValidation.requireText(title, 200, "DECISION_TITLE", "决定标题");
        String safeBody = CollabValidation.optionalText(body, 20000, "DECISION_BODY", "决定内容");
        return transactions.inTenant(context -> {
            UUID id = store.insertDecision(orgUnitId, meetingId, safeTitle, safeBody,
                    decidedAt == null ? LocalDate.now() : decidedAt, viewer.personId());
            return store.decisions(viewer, orgUnitId, 100).stream()
                    .filter(decision -> decision.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        });
    }

    private CollaborationStore.Meeting requireReadable(CollabViewer viewer, UUID meetingId) {
        return transactions.inTenant(context -> {
            CollaborationStore.Meeting meeting = store.meeting(meetingId).orElseThrow(() ->
                    ApiException.notFound("MEETING_NOT_FOUND", "会议不存在"));
            if (!viewer.seesUnit(meeting.orgUnitId())) {
                throw ApiException.forbidden("MEETING_FORBIDDEN", "无权访问该会议");
            }
            return meeting;
        });
    }
}
