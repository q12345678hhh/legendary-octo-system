package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 主题帖、评论、@ 提及与回执。 */
@Service
public class PostService {

    private final CollaborationStore store;
    private final TenantTransactions transactions;
    private final ChannelService channels;

    public PostService(CollaborationStore store, TenantTransactions transactions,
                       ChannelService channels) {
        this.store = store;
        this.transactions = transactions;
        this.channels = channels;
    }

    public record PostDetail(CollaborationStore.Post post,
                             List<CollaborationStore.Comment> comments,
                             List<UUID> receipts) {
    }

    public List<CollaborationStore.Post> list(CollabViewer viewer, UUID channelId, int limit) {
        channels.requireReadable(viewer, channelId);
        return transactions.inTenant(context -> store.posts(channelId, limit));
    }

    public PostDetail detail(CollabViewer viewer, UUID postId) {
        CollaborationStore.Post post = requirePost(viewer, postId);
        return transactions.inTenant(context -> new PostDetail(post, store.comments(postId),
                store.receipts(postId)));
    }

    public CollaborationStore.Post create(CollabViewer viewer, UUID channelId, String title,
                                          String body, List<UUID> mentionPersonIds) {
        CollaborationStore.Channel channel = channels.requireReadable(viewer, channelId);
        String safeTitle = CollabValidation.requireText(title, 200, "POST_TITLE", "帖子标题");
        String safeBody = CollabValidation.optionalText(body, 20000, "POST_BODY", "帖子正文");
        return transactions.inTenant(context -> {
            UUID postId = store.insertPost(channelId, viewer.personId(), safeTitle, safeBody);
            notifyMentions(channel, channelId, postId, null, mentionPersonIds, viewer.personId());
            return store.post(postId).orElseThrow();
        });
    }

    public CollaborationStore.Comment comment(CollabViewer viewer, UUID postId, String body,
                                              List<UUID> mentionPersonIds) {
        CollaborationStore.Post post = requirePost(viewer, postId);
        String safeBody = CollabValidation.requireText(body, 5000, "COMMENT_BODY", "评论");
        return transactions.inTenant(context -> {
            UUID commentId = store.insertComment(postId, viewer.personId(), safeBody);
            CollaborationStore.Channel channel = store.channel(post.channelId()).orElseThrow();
            notifyMentions(channel, post.channelId(), postId, commentId, mentionPersonIds,
                    viewer.personId());
            return store.comments(postId).stream()
                    .filter(comment -> comment.id().equals(commentId))
                    .findFirst()
                    .orElseThrow();
        });
    }

    public void pin(CollabViewer viewer, UUID postId) {
        requirePost(viewer, postId);
        transactions.inTenant(context -> {
            store.pinPost(postId);
            return null;
        });
    }

    public void archive(CollabViewer viewer, UUID postId) {
        requirePost(viewer, postId);
        transactions.inTenant(context -> {
            store.archivePost(postId);
            return null;
        });
    }

    public void acknowledge(CollabViewer viewer, UUID postId) {
        requirePost(viewer, postId);
        transactions.inTenant(context -> {
            store.acknowledge(postId, viewer.personId());
            return null;
        });
    }

    public List<UUID> receipts(CollabViewer viewer, UUID postId) {
        requirePost(viewer, postId);
        return transactions.inTenant(context -> store.receipts(postId));
    }

    private CollaborationStore.Post requirePost(CollabViewer viewer, UUID postId) {
        return transactions.inTenant(context -> {
            CollaborationStore.Post post = store.post(postId).orElseThrow(() ->
                    ApiException.notFound("POST_NOT_FOUND", "帖子不存在"));
            channels.requireReadable(viewer, post.channelId());
            return post;
        });
    }

    private void notifyMentions(CollaborationStore.Channel channel, UUID channelId, UUID postId,
                                UUID commentId, List<UUID> mentioned, UUID authorPersonId) {
        if (mentioned == null || mentioned.isEmpty()) {
            return;
        }
        Set<UUID> unique = new LinkedHashSet<>(mentioned);
        for (UUID personId : unique) {
            if (personId == null || personId.equals(authorPersonId)) {
                continue;
            }
            // 只通知仍在频道内的成员：通知写库前必须先确认收件人仍有读权限
            if (!store.activeChannelMember(channelId, personId)) {
                continue;
            }
            store.insertMention(postId, commentId, personId);
            store.insertNotification(personId, "post", postId,
                    "你在频道「" + channel.name() + "」被提及",
                    commentId == null ? "新的主题帖提到了你" : "新的评论提到了你");
        }
    }
}
