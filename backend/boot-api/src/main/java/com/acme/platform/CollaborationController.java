package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.collaboration.ChannelService;
import com.acme.platform.collaboration.CollabViewer;
import com.acme.platform.collaboration.CollaborationStore;
import com.acme.platform.collaboration.MeetingService;
import com.acme.platform.collaboration.PageService;
import com.acme.platform.collaboration.PostService;
import com.acme.platform.collaboration.ProjectService;
import com.acme.platform.collaboration.RevocationService;
import com.acme.platform.collaboration.ShareLinkService;
import com.acme.platform.collaboration.TaskService;
import com.acme.platform.platform.file.FileRecord;
import com.acme.platform.platform.file.FileService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门协作：频道、帖子、任务、会议、知识页与文件引用。
 *
 * <p>所有读写都先经过统一授权，再把范围下推给协作服务；文件引用只增加引用，
 * 下载仍需回到文件服务按所有者重新鉴权。
 */
@RestController
@RequestMapping("/api/v1/collaboration")
class CollaborationController {

    private static final String CHANNEL_READ = "collaboration.channel.read";
    private static final String CHANNEL_MANAGE = "collaboration.channel.manage";
    private static final String POST_WRITE = "collaboration.post.write";
    private static final String TASK_WRITE = "collaboration.task.write";
    private static final String PAGE_MANAGE = "collaboration.page.manage";

    private final ChannelService channels;
    private final PostService posts;
    private final ProjectService projects;
    private final TaskService tasks;
    private final MeetingService meetings;
    private final PageService pages;
    private final ShareLinkService shareLinks;
    private final AuthorizationGuard authorization;
    private final CollabActorResolver actors;
    private final FileService files;

    CollaborationController(ChannelService channels, PostService posts, ProjectService projects,
                            TaskService tasks, MeetingService meetings, PageService pages,
                            ShareLinkService shareLinks, AuthorizationGuard authorization,
                            CollabActorResolver actors, FileService files) {
        this.channels = channels;
        this.posts = posts;
        this.projects = projects;
        this.tasks = tasks;
        this.meetings = meetings;
        this.pages = pages;
        this.shareLinks = shareLinks;
        this.authorization = authorization;
        this.actors = actors;
        this.files = files;
    }

    record ChannelRequest(UUID orgUnitId, String scope, String name, String topic) {
    }

    record MemberRequest(UUID personId, String role) {
    }

    record PostRequest(String title, String body, List<UUID> mentionPersonIds) {
    }

    record CommentRequest(String body, List<UUID> mentionPersonIds) {
    }

    record ProjectRequest(UUID orgUnitId, String name, String description) {
    }

    record FileLinkRequest(UUID channelId, UUID postId, UUID projectId, UUID fileId) {
    }

    record TaskRequest(UUID projectId, UUID orgUnitId, UUID milestoneId, String title,
                       String description, UUID assigneePersonId, LocalDate dueDate) {
    }

    record TaskStatusRequest(String status) {
    }

    record MilestoneRequest(String name, LocalDate dueDate) {
    }

    record MeetingRequest(UUID orgUnitId, UUID projectId, String title, LocalDate meetingDate,
                          String notes) {
    }

    record ActionItemRequest(String title, UUID ownerPersonId, LocalDate dueDate) {
    }

    record DecisionRequest(UUID orgUnitId, UUID meetingId, String title, String body,
                           LocalDate decidedAt) {
    }

    record PageRequest(UUID orgUnitId, UUID parentId, String title, String body) {
    }

    record ShareLinkRequest(String resourceType, UUID resourceId, OffsetDateTime expiresAt) {
    }

    record TagRequest(String tag) {
    }

    record VersionRequest(UUID previousFileId) {
    }

    record ChannelDetail(CollaborationStore.Channel channel,
                         List<CollaborationStore.Member> members,
                         List<CollaborationStore.Post> posts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FileLinkView(UUID id, UUID channelId, UUID postId, UUID projectId, UUID fileId,
                        String fileName, String contentType, long sizeBytes,
                        OffsetDateTime createdAt) {
    }

    // ------------------------------------------------------------------ 频道

    @GetMapping("/channels")
    List<CollaborationStore.Channel> channels(@RequestParam(defaultValue = "50") int limit) {
        return channels.list(viewer(CHANNEL_READ, null), limit);
    }

    @PostMapping("/channels")
    CollaborationStore.Channel createChannel(@RequestBody ChannelRequest request) {
        return channels.create(viewer(CHANNEL_MANAGE, null), request.orgUnitId(), request.scope(),
                request.name(), request.topic());
    }

    @GetMapping("/channels/{channelId}")
    ChannelDetail channel(@PathVariable UUID channelId) {
        CollabViewer viewer = viewer(CHANNEL_READ, channelId);
        CollaborationStore.Channel channel = channels.requireReadable(viewer, channelId);
        return new ChannelDetail(channel, channels.members(viewer, channelId),
                posts.list(viewer, channelId, 50));
    }

    @PostMapping("/channels/{channelId}/archive")
    RevocationService.RevocationResult archiveChannel(@PathVariable UUID channelId) {
        return channels.archive(viewer(CHANNEL_MANAGE, channelId), channelId);
    }

    @GetMapping("/channels/{channelId}/members")
    List<CollaborationStore.Member> members(@PathVariable UUID channelId) {
        return channels.members(viewer(CHANNEL_READ, channelId), channelId);
    }

    @PostMapping("/channels/{channelId}/members")
    CollaborationStore.Member addMember(@PathVariable UUID channelId,
                                        @RequestBody MemberRequest request) {
        return channels.addMember(viewer(CHANNEL_MANAGE, channelId), channelId,
                request.personId(), request.role());
    }

    @DeleteMapping("/channels/{channelId}/members/{personId}")
    RevocationService.RevocationResult removeMember(@PathVariable UUID channelId,
                                                    @PathVariable UUID personId) {
        return channels.removeMember(viewer(CHANNEL_MANAGE, channelId), channelId, personId);
    }

    // ------------------------------------------------------------------ 帖子与评论

    @GetMapping("/channels/{channelId}/posts")
    List<CollaborationStore.Post> posts(@PathVariable UUID channelId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return posts.list(viewer(CHANNEL_READ, channelId), channelId, limit);
    }

    @PostMapping("/channels/{channelId}/posts")
    CollaborationStore.Post createPost(@PathVariable UUID channelId,
                                       @RequestBody PostRequest request) {
        return posts.create(viewer(POST_WRITE, channelId), channelId, request.title(),
                request.body(), request.mentionPersonIds());
    }

    @GetMapping("/posts/{postId}")
    PostService.PostDetail post(@PathVariable UUID postId) {
        return posts.detail(viewer(CHANNEL_READ, postId), postId);
    }

    @PostMapping("/posts/{postId}/comments")
    CollaborationStore.Comment comment(@PathVariable UUID postId,
                                        @RequestBody CommentRequest request) {
        return posts.comment(viewer(POST_WRITE, postId), postId, request.body(),
                request.mentionPersonIds());
    }

    @PostMapping("/posts/{postId}/receipts")
    void acknowledge(@PathVariable UUID postId) {
        posts.acknowledge(viewer(POST_WRITE, postId), postId);
    }

    @PostMapping("/posts/{postId}/pin")
    void pin(@PathVariable UUID postId) {
        posts.pin(viewer(POST_WRITE, postId), postId);
    }

    @PostMapping("/posts/{postId}/archive")
    void archivePost(@PathVariable UUID postId) {
        posts.archive(viewer(POST_WRITE, postId), postId);
    }

    // ------------------------------------------------------------------ 项目与文件

    @GetMapping("/projects")
    List<CollaborationStore.Project> projects(@RequestParam(defaultValue = "50") int limit) {
        return projects.list(viewer(CHANNEL_READ, null), limit);
    }

    @PostMapping("/projects")
    CollaborationStore.Project createProject(@RequestBody ProjectRequest request) {
        return projects.create(viewer(CHANNEL_MANAGE, null), request.orgUnitId(), request.name(),
                request.description());
    }

    @PostMapping("/projects/{projectId}/members")
    void addProjectMember(@PathVariable UUID projectId, @RequestBody MemberRequest request) {
        projects.addMember(viewer(CHANNEL_MANAGE, projectId), projectId, request.personId(),
                request.role());
    }

    @DeleteMapping("/projects/{projectId}/members/{personId}")
    void removeProjectMember(@PathVariable UUID projectId, @PathVariable UUID personId) {
        projects.removeMember(viewer(CHANNEL_MANAGE, projectId), projectId, personId);
    }

    @GetMapping("/files")
    List<FileLinkView> fileLinks(@RequestParam(required = false) UUID channelId,
                                 @RequestParam(required = false) UUID projectId,
                                 @RequestParam(defaultValue = "50") int limit) {
        CollabViewer viewer = viewer(CHANNEL_READ, channelId == null ? projectId : channelId);
        return projects.fileLinks(viewer, channelId, projectId, limit).stream()
                .map(this::fileLinkView)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @PostMapping("/files")
    CollaborationStore.FileLink linkFile(@RequestBody FileLinkRequest request) {
        CollabViewer viewer = viewer(CHANNEL_READ,
                request.channelId() == null ? request.projectId() : request.channelId());
        authoriseFile(request.fileId());
        return projects.linkFile(viewer, request.channelId(), request.postId(),
                request.projectId(), request.fileId());
    }

    @PostMapping("/files/{fileId}/versions")
    void addFileVersion(@PathVariable UUID fileId, @RequestBody VersionRequest request) {
        authoriseFile(fileId);
        projects.addVersion(viewer(CHANNEL_READ, null), fileId, request.previousFileId());
    }

    @PostMapping("/files/{fileId}/tags")
    void addFileTag(@PathVariable UUID fileId, @RequestBody TagRequest request) {
        authoriseFile(fileId);
        projects.addTag(viewer(CHANNEL_READ, null), fileId, request.tag());
    }

    @GetMapping("/files/{fileId}/tags")
    List<String> fileTags(@PathVariable UUID fileId) {
        authoriseFile(fileId);
        return projects.tags(viewer(CHANNEL_READ, null), fileId);
    }

    // ------------------------------------------------------------------ 任务与里程碑

    @GetMapping("/tasks")
    List<CollaborationStore.Task> tasks(@RequestParam(required = false) UUID projectId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "100") int limit) {
        return tasks.list(viewer(CHANNEL_READ, projectId), projectId, status, limit);
    }

    @PostMapping("/tasks")
    CollaborationStore.Task createTask(@RequestBody TaskRequest request) {
        return tasks.create(viewer(TASK_WRITE, request.projectId()), request.projectId(),
                request.orgUnitId(), request.milestoneId(), request.title(),
                request.description(), request.assigneePersonId(), request.dueDate());
    }

    @PatchMapping("/tasks/{taskId}/status")
    CollaborationStore.Task updateTaskStatus(@PathVariable UUID taskId,
                                             @RequestBody TaskStatusRequest request) {
        return tasks.updateStatus(viewer(TASK_WRITE, taskId), taskId, request.status());
    }

    @GetMapping("/projects/{projectId}/milestones")
    List<CollaborationStore.Milestone> milestones(@PathVariable UUID projectId) {
        return tasks.milestones(viewer(CHANNEL_READ, projectId), projectId);
    }

    @PostMapping("/projects/{projectId}/milestones")
    CollaborationStore.Milestone createMilestone(@PathVariable UUID projectId,
                                                 @RequestBody MilestoneRequest request) {
        return tasks.createMilestone(viewer(TASK_WRITE, projectId), projectId, request.name(),
                request.dueDate());
    }

    // ------------------------------------------------------------------ 会议、行动项、决定

    @GetMapping("/meetings")
    List<CollaborationStore.Meeting> meetings(@RequestParam(required = false) UUID orgUnitId,
                                              @RequestParam(defaultValue = "50") int limit) {
        return meetings.list(viewer(CHANNEL_READ, null), orgUnitId, limit);
    }

    @PostMapping("/meetings")
    CollaborationStore.Meeting createMeeting(@RequestBody MeetingRequest request) {
        return meetings.create(viewer(POST_WRITE, null), request.orgUnitId(), request.projectId(),
                request.title(), request.meetingDate(), request.notes());
    }

    @GetMapping("/meetings/{meetingId}")
    MeetingService.MeetingDetail meeting(@PathVariable UUID meetingId) {
        return meetings.detail(viewer(CHANNEL_READ, meetingId), meetingId);
    }

    @PostMapping("/meetings/{meetingId}/action-items")
    CollaborationStore.ActionItem addActionItem(@PathVariable UUID meetingId,
                                                 @RequestBody ActionItemRequest request) {
        return meetings.addActionItem(viewer(POST_WRITE, meetingId), meetingId, request.title(),
                request.ownerPersonId(), request.dueDate());
    }

    @GetMapping("/action-items")
    List<CollaborationStore.ActionItem> myActionItems(
            @RequestParam(defaultValue = "50") int limit) {
        return meetings.myActionItems(viewer(CHANNEL_READ, null), limit);
    }

    @PostMapping("/action-items/{actionItemId}/complete")
    CollaborationStore.ActionItem completeActionItem(@PathVariable UUID actionItemId) {
        return meetings.completeActionItem(viewer(TASK_WRITE, actionItemId), actionItemId);
    }

    @GetMapping("/decisions")
    List<CollaborationStore.Decision> decisions(@RequestParam(required = false) UUID orgUnitId,
                                                @RequestParam(defaultValue = "50") int limit) {
        return meetings.decisions(viewer(CHANNEL_READ, null), orgUnitId, limit);
    }

    @PostMapping("/decisions")
    CollaborationStore.Decision createDecision(@RequestBody DecisionRequest request) {
        return meetings.createDecision(viewer(POST_WRITE, null), request.orgUnitId(),
                request.meetingId(), request.title(), request.body(), request.decidedAt());
    }

    // ------------------------------------------------------------------ 知识页与分享

    @GetMapping("/pages")
    List<CollaborationStore.Page> pages(@RequestParam(required = false) UUID orgUnitId,
                                        @RequestParam(defaultValue = "100") int limit) {
        return pages.list(viewer(CHANNEL_READ, null), orgUnitId, limit);
    }

    @PostMapping("/pages")
    CollaborationStore.Page createPage(@RequestBody PageRequest request) {
        return pages.create(viewer(PAGE_MANAGE, null), request.orgUnitId(), request.parentId(),
                request.title(), request.body());
    }

    @PatchMapping("/pages/{pageId}")
    CollaborationStore.Page updatePage(@PathVariable UUID pageId,
                                       @RequestBody PageRequest request) {
        return pages.update(viewer(PAGE_MANAGE, pageId), pageId, request.title(), request.body());
    }

    @PostMapping("/share-links")
    ShareLinkService.CreatedShareLink createShareLink(@RequestBody ShareLinkRequest request) {
        return shareLinks.create(viewer(CHANNEL_READ, request.resourceId()),
                request.resourceType(), request.resourceId(), request.expiresAt());
    }

    @GetMapping("/share-links/{token}")
    ShareLinkService.ResolvedShareLink resolveShareLink(@PathVariable String token) {
        return shareLinks.resolve(viewer(CHANNEL_READ, null), token);
    }

    // ------------------------------------------------------------------ 辅助

    private CollabViewer viewer(String permission, UUID resourceId) {
        AccessDecision decision = authorization.require(permission, "collaboration",
                resourceId, Map.of());
        return actors.viewer(decision);
    }

    private void authoriseFile(UUID fileId) {
        FileRecord file = files.find(fileId);
        authorization.require("file.read", "file", fileId, Map.of("ownerId", file.subjectId()));
    }

    private FileLinkView fileLinkView(CollaborationStore.FileLink link) {
        try {
            FileRecord file = files.find(link.fileId());
            AccessDecision decision = authorization.decide("file.read", "file", link.fileId(),
                    Map.of("ownerId", file.subjectId()));
            if (!decision.allowed()) {
                return null;
            }
            return new FileLinkView(link.id(), link.channelId(), link.postId(), link.projectId(),
                    link.fileId(), file.fileName(), file.contentType(), file.sizeBytes(),
                    link.createdAt());
        } catch (com.acme.platform.platform.ApiException notFound) {
            return null;
        }
    }
}
