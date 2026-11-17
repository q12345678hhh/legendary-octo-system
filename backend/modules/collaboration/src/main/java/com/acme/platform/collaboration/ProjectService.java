package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 项目空间、文件引用、版本与标签；文件引用不改变下载 ACL。 */
@Service
public class ProjectService {

    private final CollaborationStore store;
    private final TenantTransactions transactions;
    private final ChannelService channels;

    public ProjectService(CollaborationStore store, TenantTransactions transactions,
                          ChannelService channels) {
        this.store = store;
        this.transactions = transactions;
        this.channels = channels;
    }

    public List<CollaborationStore.Project> list(CollabViewer viewer, int limit) {
        return transactions.inTenant(context -> store.projects(viewer, limit));
    }

    public CollaborationStore.Project requireReadable(CollabViewer viewer, UUID projectId) {
        return transactions.inTenant(context -> {
            CollaborationStore.Project project = store.project(projectId).orElseThrow(() ->
                    ApiException.notFound("PROJECT_NOT_FOUND", "项目不存在或已归档"));
            if (!store.projectReadable(viewer, projectId)) {
                throw ApiException.forbidden("PROJECT_FORBIDDEN", "无权访问该项目");
            }
            return project;
        });
    }

    public CollaborationStore.Project create(CollabViewer viewer, UUID orgUnitId, String name,
                                             String description) {
        if (!viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("PROJECT_UNIT_FORBIDDEN", "只能在本部门创建项目");
        }
        String safeName = CollabValidation.requireText(name, 120, "PROJECT_NAME", "项目名称");
        String safeDescription = CollabValidation.optionalText(description, 5000,
                "PROJECT_DESCRIPTION", "项目说明");
        return transactions.inTenant(context -> {
            UUID projectId = store.insertProject(orgUnitId, safeName, safeDescription);
            if (viewer.personId() != null) {
                store.upsertProjectMember(projectId, viewer.personId(), "owner");
            }
            return store.project(projectId).orElseThrow();
        });
    }

    public List<UUID> members(CollabViewer viewer, UUID projectId) {
        requireReadable(viewer, projectId);
        return transactions.inTenant(context -> store.projectMembers(projectId));
    }

    public void addMember(CollabViewer viewer, UUID projectId, UUID personId, String role) {
        requireReadable(viewer, projectId);
        if (personId == null) {
            throw ApiException.badRequest("PROJECT_MEMBER_REQUIRED", "缺少成员标识");
        }
        String normalizedRole = role == null || role.isBlank() ? "member" : role.strip();
        if (!"owner".equals(normalizedRole) && !"member".equals(normalizedRole)) {
            throw ApiException.badRequest("PROJECT_MEMBER_ROLE_INVALID",
                    "成员角色只能是 owner 或 member");
        }
        transactions.inTenant(context -> {
            store.upsertProjectMember(projectId, personId, normalizedRole);
            return null;
        });
    }

    public void removeMember(CollabViewer viewer, UUID projectId, UUID personId) {
        requireReadable(viewer, projectId);
        transactions.inTenant(context -> {
            store.removeProjectMember(projectId, personId);
            return null;
        });
    }

    public List<CollaborationStore.FileLink> fileLinks(CollabViewer viewer, UUID channelId,
                                                        UUID projectId, int limit) {
        if (channelId != null) {
            channels.requireReadable(viewer, channelId);
        } else if (projectId != null) {
            requireReadable(viewer, projectId);
        } else {
            throw ApiException.badRequest("FILE_LINK_TARGET_REQUIRED", "必须指定频道或项目");
        }
        return transactions.inTenant(context -> store.fileLinks(channelId, projectId, limit));
    }

    public CollaborationStore.FileLink linkFile(CollabViewer viewer, UUID channelId, UUID postId,
                                                UUID projectId, UUID fileId) {
        if (fileId == null) {
            throw ApiException.badRequest("FILE_ID_REQUIRED", "缺少文件标识");
        }
        if (channelId == null && postId == null && projectId == null) {
            throw ApiException.badRequest("FILE_LINK_TARGET_REQUIRED", "必须指定频道、帖子或项目");
        }
        UUID resolvedChannel = channelId;
        if (postId != null) {
            CollaborationStore.Post post = transactions.inTenant(context ->
                    store.post(postId).orElseThrow(() ->
                            ApiException.notFound("POST_NOT_FOUND", "帖子不存在")));
            resolvedChannel = post.channelId();
        }
        if (resolvedChannel != null) {
            channels.requireReadable(viewer, resolvedChannel);
        }
        if (projectId != null) {
            requireReadable(viewer, projectId);
        }
        UUID targetChannel = resolvedChannel;
        return transactions.inTenant(context -> {
            UUID id = store.insertFileLink(targetChannel, postId, projectId, fileId,
                    viewer.personId());
            return store.fileLinks(targetChannel, projectId, 100).stream()
                    .filter(link -> link.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        });
    }

    public void addVersion(CollabViewer viewer, UUID fileId, UUID previousFileId) {
        if (fileId == null || previousFileId == null) {
            throw ApiException.badRequest("FILE_VERSION_REQUIRED", "缺少文件或上一版本标识");
        }
        transactions.inTenant(context -> {
            store.insertFileVersion(fileId, previousFileId, 1);
            return null;
        });
    }

    public void addTag(CollabViewer viewer, UUID fileId, String tag) {
        String safeTag = CollabValidation.requireText(tag, 40, "FILE_TAG", "标签");
        transactions.inTenant(context -> {
            store.insertFileTag(fileId, safeTag);
            return null;
        });
    }

    public List<String> tags(CollabViewer viewer, UUID fileId) {
        return transactions.inTenant(context -> store.fileTags(fileId));
    }
}
