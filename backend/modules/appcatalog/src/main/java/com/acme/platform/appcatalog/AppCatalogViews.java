package com.acme.platform.appcatalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 模块目录与建议 API 的出参视图：控制器与前端共用同一组字段名。 */
public final class AppCatalogViews {

    private AppCatalogViews() {
    }

    public record ModuleView(UUID id, String code, String name, String category,
                             String description, String templateKey, UUID ownerPersonId,
                             String version, String dependencies, String applicableScope,
                             String healthStatus, String installStatus,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record SuggestionView(UUID id, String title, UUID requesterPersonId,
                                 String businessProblem, String goal, String scope,
                                 String dataScope, String permissionsNeeded, String costEstimate,
                                 String benefitEstimate, UUID suggestedOwnerPersonId,
                                 UUID configOwnerPersonId, String status,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record EventView(UUID id, String eventKind, String fromStatus, String toStatus,
                            String action, UUID actorPersonId, UUID recipientPersonId,
                            String comment, OffsetDateTime occurredAt) {
    }

    public record SuggestionDetail(SuggestionView suggestion, List<EventView> events) {
    }

    static ModuleView module(AppCatalogStore.ModuleRow row) {
        return new ModuleView(row.id(), row.code(), row.name(), row.category(), row.description(),
                row.templateKey(), row.ownerPersonId(), row.version(), row.dependencies(),
                row.applicableScope(), row.healthStatus(), row.installStatus(), row.createdAt(),
                row.updatedAt());
    }

    static SuggestionView suggestion(AppCatalogStore.SuggestionRow row) {
        return new SuggestionView(row.id(), row.title(), row.requesterPersonId(),
                row.businessProblem(), row.goal(), row.scope(), row.dataScope(),
                row.permissionsNeeded(), row.costEstimate(), row.benefitEstimate(),
                row.suggestedOwnerPersonId(), row.configOwnerPersonId(), row.status(),
                row.createdAt(), row.updatedAt());
    }

    static EventView event(AppCatalogStore.EventRow row) {
        return new EventView(row.id(), row.eventKind(), row.fromStatus(), row.toStatus(),
                row.action(), row.actorPersonId(), row.recipientPersonId(), row.comment(),
                row.occurredAt());
    }
}
