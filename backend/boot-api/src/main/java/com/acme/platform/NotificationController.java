package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.collaboration.CollabViewer;
import com.acme.platform.collaboration.NotificationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 站内通知箱：只返回当前主体自己的通知。 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;
    private final AuthorizationGuard authorization;
    private final CollabActorResolver actors;

    NotificationController(NotificationService notifications, AuthorizationGuard authorization,
                           CollabActorResolver actors) {
        this.notifications = notifications;
        this.authorization = authorization;
        this.actors = actors;
    }

    @GetMapping
    NotificationService.Inbox inbox(@RequestParam(defaultValue = "50") int limit) {
        AccessDecision decision = authorization.require("search.read", "notification", null,
                Map.of());
        CollabViewer viewer = actors.viewer(decision);
        return notifications.inbox(viewer, limit);
    }

    @PostMapping("/{notificationId}/read")
    void markRead(@PathVariable UUID notificationId) {
        AccessDecision decision = authorization.require("search.read", "notification",
                notificationId, Map.of());
        notifications.markRead(actors.viewer(decision), notificationId);
    }
}
