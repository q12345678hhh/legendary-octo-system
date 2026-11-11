package com.acme.platform.organization;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 部门移动的影响预览与票据校验。
 *
 * <p>移动是高危操作：先出预览，再由用户确认票据完成移动。票据 10 分钟过期、只能消费一次，
 * 移动时服务端重新计算所有计数；任何一项与票据不一致都拒绝，避免「预览时 3 个人，落库时 30 个人」。
 */
@Service
public class ImpactPreviewService {

    private final TenantTransactions tenantTransactions;
    private final OrganizationStructureStore store;
    private final ImpactPreviewStore previewStore;
    private final OrganizationCalendar calendar;
    private final OrganizationProperties properties;
    private final Clock clock;

    public ImpactPreviewService(TenantTransactions tenantTransactions,
                                OrganizationStructureStore store,
                                ImpactPreviewStore previewStore,
                                OrganizationCalendar calendar,
                                OrganizationProperties properties,
                                Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
        this.previewStore = previewStore;
        this.calendar = calendar;
        this.properties = properties;
        this.clock = clock;
    }

    public ImpactPreview previewMove(UUID unitId, UUID targetParentId) {
        return tenantTransactions.inTenant(ctx -> {
            OrgUnit unit = store.findUnit(unitId).orElseThrow(() ->
                    ApiException.notFound("ORG_UNIT_NOT_FOUND", "部门不存在：" + unitId));
            if (targetParentId != null) {
                OrgUnit parent = store.findUnit(targetParentId).orElseThrow(() ->
                        ApiException.unprocessable("ORG_UNIT_NOT_FOUND", "目标父部门不存在：" + targetParentId));
                if (parent.status() == OrgUnit.UnitStatus.ARCHIVED) {
                    throw ApiException.unprocessable("ORG_UNIT_ARCHIVED", "已归档部门不能作为父部门");
                }
            }
            OrgTreeRules.ensureNoCycle(unitId, targetParentId,
                    candidate -> store.findParentId(candidate));

            LocalDate today = calendar.today(unit.tenantId());
            Counts counts = counts(unitId, today);
            UUID id = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(clock);
            previewStore.insert(id, unitId, targetParentId, counts.descendants(), counts.employments(),
                    counts.positions(), counts.reportingLines(), counts.delegations(),
                    counts.scheduledChanges(), OrganizationStructureService.actor(),
                    now.plus(properties.getPreviewTtl()));
            return previewStore.find(id).orElseThrow();
        });
    }

    /**
     * 校验并消费票据。必须在移动的同一个事务里调用：
     * 校验通过 → 立即消费 → 调用方执行移动，三步要么一起提交，要么一起回滚。
     */
    public ImpactPreview consumeForMove(UUID previewId, UUID unitId, UUID targetParentId) {
        if (previewId == null) {
            throw ApiException.unprocessable("ORG_IMPACT_PREVIEW_REQUIRED",
                    "移动部门前必须先调用影响预览，并携带 previewId");
        }
        return tenantTransactions.inTenant(ctx -> {
            ImpactPreview preview = previewStore.find(previewId).orElseThrow(() ->
                    ApiException.unprocessable("ORG_IMPACT_PREVIEW_NOT_FOUND", "预览票据不存在：" + previewId));
            if (preview.consumed()) {
                throw ApiException.conflict("ORG_IMPACT_PREVIEW_CONSUMED",
                        "预览票据已被使用，请重新生成");
            }
            if (preview.expiresAt().isBefore(OffsetDateTime.now(clock))) {
                throw ApiException.unprocessable("ORG_IMPACT_PREVIEW_EXPIRED",
                        "预览票据已过期，请重新生成");
            }
            if (!preview.unitId().equals(unitId)
                    || !java.util.Objects.equals(preview.targetParentId(), targetParentId)) {
                throw ApiException.unprocessable("ORG_IMPACT_PREVIEW_STALE",
                        "预览票据与本次移动的目标不一致，请重新生成");
            }

            Counts current = counts(unitId, calendar.today(preview.tenantId()));
            if (current.descendants() != preview.descendantCount()
                    || current.employments() != preview.employmentCount()
                    || current.positions() != preview.positionCount()
                    || current.reportingLines() != preview.reportingLineCount()
                    || current.delegations() != preview.delegationCount()
                    || current.scheduledChanges() != preview.scheduledChangeCount()) {
                throw ApiException.unprocessable("ORG_IMPACT_PREVIEW_STALE",
                        "组织数据在预览之后发生了变化，请重新预览：" + current + " / " + preview);
            }
            if (previewStore.consume(previewId) == 0) {
                throw ApiException.conflict("ORG_IMPACT_PREVIEW_CONSUMED",
                        "预览票据已被其他请求使用，请重新生成");
            }
            return preview;
        });
    }

    private Counts counts(UUID unitId, LocalDate today) {
        return new Counts(
                store.countDescendants(unitId),
                store.countEmploymentsInSubtree(unitId, today),
                store.countActivePositionsInSubtree(unitId),
                store.countReportingLinesInSubtree(unitId, today),
                store.countDelegationsInSubtree(unitId, today),
                store.countScheduledChangesInSubtree(unitId));
    }

    /** 定时移动在创建变更单时冻结计数快照；到期应用前重新计算，变了就失败而不是悄悄执行。 */
    public void verifySnapshot(UUID unitId, java.util.Map<String, Object> snapshot) {
        tenantTransactions.inTenant(ctx -> {
            UUID tenantId = com.acme.platform.platform.RequestContextHolder.current().tenantId();
            Counts current = counts(unitId, calendar.today(tenantId));
            if (!current.matches(snapshot)) {
                throw ApiException.unprocessable("ORG_IMPACT_PREVIEW_STALE",
                        "组织数据在变更排期后发生了变化，计划移动已标记失败，请重新预览");
            }
            return null;
        });
    }

    private record Counts(int descendants, int employments, int positions, int reportingLines,
                          int delegations, int scheduledChanges) {

        boolean matches(java.util.Map<String, Object> snapshot) {
            return equalsNumber(snapshot.get("descendantCount"), descendants)
                    && equalsNumber(snapshot.get("employmentCount"), employments)
                    && equalsNumber(snapshot.get("positionCount"), positions)
                    && equalsNumber(snapshot.get("reportingLineCount"), reportingLines)
                    && equalsNumber(snapshot.get("delegationCount"), delegations);
        }

        private static boolean equalsNumber(Object value, int expected) {
            return value instanceof Number number && number.intValue() == expected;
        }
    }
}
