package com.acme.platform.organization;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 组织变更单应用服务。
 *
 * <p>变更单是「未来生效」的唯一入口：结构实体不直接保存未来状态，避免时间一到
 * 还要靠读侧到处判断。到期日已到的变更立即应用并写历史；未来的变更进入系统级到期队列，
 * 由 Worker 在租户上下文里原子切换。
 */
@Service
public class OrganizationChangeService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationChangeService.class);

    private final TenantTransactions tenantTransactions;
    private final OrganizationChangeStore store;
    private final OrganizationStructureService structureService;
    private final OrganizationRelationService relationService;
    private final ImpactPreviewService previewService;
    private final OrganizationCalendar calendar;
    private final OrganizationHistoryStore history;

    public OrganizationChangeService(TenantTransactions tenantTransactions,
                                     OrganizationChangeStore store,
                                     OrganizationStructureService structureService,
                                     OrganizationRelationService relationService,
                                     ImpactPreviewService previewService,
                                     OrganizationCalendar calendar,
                                     OrganizationHistoryStore history) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
        this.structureService = structureService;
        this.relationService = relationService;
        this.previewService = previewService;
        this.calendar = calendar;
        this.history = history;
    }

    public ChangeResult create(CreateChangeCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            UUID tenantId = RequestContextHolder.current().tenantId();
            if (command.changeType() == null) {
                throw ApiException.unprocessable("CHANGE_TYPE_REQUIRED", "必须提供 changeType");
            }
            if (command.effectiveDate() == null) {
                throw ApiException.unprocessable("EFFECTIVE_DATE_REQUIRED", "必须提供生效日期");
            }
            Map<String, Object> payload = command.payload() == null ? Map.of() : command.payload();
            payload = preparePayload(command, payload);

            UUID id = UUID.randomUUID();
            store.insert(id, command.changeType(), command.targetId(), command.effectiveDate(),
                    payload, OrganizationStructureService.actor(), command.reason());

            LocalDate today = calendar.today(tenantId);
            boolean appliedImmediately = !command.effectiveDate().isAfter(today);
            OrganizationChange change;
            if (appliedImmediately) {
                change = store.lock(id).orElseThrow();
                applyLocked(change);
                // 应用会改状态，返回前重新读取，避免把 scheduled 的旧快照交给调用方
                change = store.find(id).orElseThrow();
            } else {
                store.enqueue(id, tenantId, command.effectiveDate());
                change = store.find(id).orElseThrow();
            }
            return new ChangeResult(change, appliedImmediately);
        });
    }

    /**
     * 预处理 payload：定时移动必须在排期时校验并消费预览票据，并把计数快照冻结进 payload，
     * 到期应用时再核对一次；结构创建/更新则补上默认值。
     */
    private Map<String, Object> preparePayload(CreateChangeCommand command, Map<String, Object> payload) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(payload);
        if (command.changeType() == OrganizationChange.ChangeType.UNIT_MOVE) {
            UUID previewId = uuid(result, "previewId");
            if (previewId == null) {
                throw ApiException.unprocessable("ORG_IMPACT_PREVIEW_REQUIRED",
                        "排期移动部门前必须先做影响预览，并在 payload.previewId 传入票据");
            }
            UUID targetParentId = uuid(result, "targetParentId");
            ImpactPreview preview = previewService.consumeForMove(previewId, command.targetId(),
                    targetParentId);
            result.put("descendantCount", preview.descendantCount());
            result.put("employmentCount", preview.employmentCount());
            result.put("positionCount", preview.positionCount());
            result.put("reportingLineCount", preview.reportingLineCount());
            result.put("delegationCount", preview.delegationCount());
            result.remove("previewId");
        }
        return result;
    }

    public List<OrganizationChange> list(OrganizationChange.ChangeStatus status, UUID targetId) {
        return tenantTransactions.inTenant(ctx -> store.list(status, targetId));
    }

    public OrganizationChange get(UUID id) {
        return tenantTransactions.inTenant(ctx -> store.find(id).orElseThrow(() ->
                ApiException.notFound("ORGANIZATION_CHANGE_NOT_FOUND", "变更单不存在：" + id)));
    }

    public OrganizationChange cancel(UUID id) {
        return tenantTransactions.inTenant(ctx -> {
            OrganizationChange change = store.find(id).orElseThrow(() ->
                    ApiException.notFound("ORGANIZATION_CHANGE_NOT_FOUND", "变更单不存在：" + id));
            if (change.status() != OrganizationChange.ChangeStatus.SCHEDULED) {
                throw ApiException.conflict("ORGANIZATION_CHANGE_NOT_SCHEDULED",
                        "只有待执行的变更可以撤销，当前状态：" + change.status().wire());
            }
            store.cancel(id);
            store.dequeue(id);
            return store.find(id).orElseThrow();
        });
    }

    /**
     * 应用一批到期变更，返回成功应用的条数。
     *
     * <p>每条都在自己的租户事务里加锁、校验、应用、出队；单条失败只标记该条为 failed，
     * 不影响同批其他租户。失败的变更不会重试：组织调整需要人工重新确认，而不是自动重放。
     */
    public int applyDue(int batchSize) {
        List<OrganizationChangeStore.DueChange> due = store.claimDue(batchSize);
        int applied = 0;
        for (OrganizationChangeStore.DueChange candidate : due) {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            RequestContext context = new RequestContext(candidate.tenantId(), null,
                    "organization-change", traceId);
            RequestContextHolder.set(context);
            try {
                Boolean changed = tenantTransactions.inTenant(context, ctx -> {
                    Optional<OrganizationChange> locked = store.lock(candidate.changeId());
                    if (locked.isEmpty()) {
                        store.dequeue(candidate.changeId());
                        return false;
                    }
                    OrganizationChange change = locked.get();
                    if (change.status() != OrganizationChange.ChangeStatus.SCHEDULED) {
                        store.dequeue(candidate.changeId());
                        return false;
                    }
                    if (change.effectiveDate().isAfter(calendar.today(candidate.tenantId()))) {
                        return false;
                    }
                    applyLocked(change);
                    store.dequeue(candidate.changeId());
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) {
                    applied++;
                }
            } catch (RuntimeException e) {
                log.warn("组织变更应用失败 changeId={} tenantId={} error={}",
                        candidate.changeId(), candidate.tenantId(), e.getMessage());
                markFailedQuietly(context, candidate.changeId(), e.getMessage());
            } finally {
                RequestContextHolder.clear();
            }
        }
        return applied;
    }

    private void markFailedQuietly(RequestContext context, UUID changeId, String reason) {
        try {
            tenantTransactions.inTenant(context, ctx -> {
                store.markFailed(changeId, reason == null ? "unknown" : reason);
                store.dequeue(changeId);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("标记组织变更失败时再次出错 changeId={} error={}", changeId, e.getMessage());
        }
    }

    public List<OrganizationHistoryEntry> history(String entityType, UUID entityId) {
        return tenantTransactions.inTenant(ctx -> history.list(entityType, entityId));
    }

    // ---------------------------------------------------------------- 应用实现

    private void applyLocked(OrganizationChange change) {
        Map<String, Object> payload = change.payload();
        switch (change.changeType()) {
            case UNIT_CREATE -> structureService.createUnit(new OrganizationStructureService.CreateUnitCommand(
                    uuid(payload, "parentId"),
                    requireUuid(payload, "legalEntityId"),
                    requireString(payload, "code"),
                    requireString(payload, "name"),
                    OrgUnit.UnitType.fromWire(string(payload, "unitType")),
                    intValue(payload, "sortOrder", 0)));
            case UNIT_UPDATE -> structureService.updateUnit(change.targetId(),
                    new OrganizationStructureService.UpdateUnitCommand(
                            requireString(payload, "name"),
                            OrgUnit.UnitType.fromWire(string(payload, "unitType")),
                            intValue(payload, "sortOrder", 0)));
            case UNIT_MOVE -> {
                previewService.verifySnapshot(change.targetId(), payload);
                structureService.applyScheduledMove(change.targetId(),
                        uuid(payload, "targetParentId"), integer(payload, "sortOrder"));
            }
            case UNIT_ARCHIVE -> structureService.archiveUnit(change.targetId());
            case LEGAL_ENTITY_UPSERT -> applyLegalEntity(payload);
            case JOB_LEVEL_UPSERT -> applyJobLevel(payload);
            case JOB_UPSERT -> applyJob(payload);
            case POSITION_UPSERT -> applyPosition(payload);
            case COST_CENTER_UPSERT -> applyCostCenter(payload);
            case LOCATION_UPSERT -> applyLocation(payload);
            case OWNER_ASSIGN -> relationService.assignOwnerRole(
                    new OrganizationRelationService.AssignOwnerRoleCommand(
                            requireUuid(payload, "employmentId"),
                            OwnerRole.RoleType.fromWire(string(payload, "roleType")),
                            date(payload, "effectiveFrom"),
                            date(payload, "effectiveTo")));
            case OWNER_REVOKE -> relationService.revokeOwnerRole(
                    requireUuid(payload, "roleId"), date(payload, "effectiveTo"));
        }
        store.markApplied(change.id());
    }

    private void applyLegalEntity(Map<String, Object> payload) {
        UUID id = uuid(payload, "id");
        if (id == null) {
            structureService.createLegalEntity(new OrganizationStructureService.CreateLegalEntityCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    string(payload, "registrationNo")));
        } else {
            structureService.updateLegalEntity(id, new OrganizationStructureService.UpdateLegalEntityCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    string(payload, "registrationNo"),
                    MasterDataStatus.fromWire(string(payload, "status"))));
        }
    }

    private void applyJobLevel(Map<String, Object> payload) {
        UUID id = uuid(payload, "id");
        if (id == null) {
            structureService.createJobLevel(new OrganizationStructureService.CreateJobLevelCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    intValue(payload, "rank", 1)));
        } else {
            structureService.updateJobLevel(id, new OrganizationStructureService.UpdateJobLevelCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    intValue(payload, "rank", 1),
                    MasterDataStatus.fromWire(string(payload, "status"))));
        }
    }

    private void applyJob(Map<String, Object> payload) {
        UUID id = uuid(payload, "id");
        if (id == null) {
            structureService.createJob(new OrganizationStructureService.CreateJobCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    string(payload, "jobFamily"), uuid(payload, "defaultLevelId")));
        } else {
            structureService.updateJob(id, new OrganizationStructureService.UpdateJobCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    string(payload, "jobFamily"), uuid(payload, "defaultLevelId"),
                    MasterDataStatus.fromWire(string(payload, "status"))));
        }
    }

    private void applyPosition(Map<String, Object> payload) {
        UUID id = uuid(payload, "id");
        if (id == null) {
            structureService.createPosition(new OrganizationStructureService.CreatePositionCommand(
                    requireUuid(payload, "orgUnitId"), requireUuid(payload, "jobId"),
                    requireString(payload, "code"), string(payload, "name"),
                    integer(payload, "headcountLimit")));
        } else {
            structureService.updatePosition(id, new OrganizationStructureService.UpdatePositionCommand(
                    requireUuid(payload, "orgUnitId"), requireUuid(payload, "jobId"),
                    requireString(payload, "code"), string(payload, "name"),
                    integer(payload, "headcountLimit"),
                    MasterDataStatus.fromWire(string(payload, "status"))));
        }
    }

    private void applyCostCenter(Map<String, Object> payload) {
        UUID id = uuid(payload, "id");
        if (id == null) {
            structureService.createCostCenter(new OrganizationStructureService.CreateCostCenterCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    requireUuid(payload, "legalEntityId")));
        } else {
            structureService.updateCostCenter(id, new OrganizationStructureService.UpdateCostCenterCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    requireUuid(payload, "legalEntityId"),
                    MasterDataStatus.fromWire(string(payload, "status"))));
        }
    }

    private void applyLocation(Map<String, Object> payload) {
        UUID id = uuid(payload, "id");
        if (id == null) {
            structureService.createLocation(new OrganizationStructureService.CreateLocationCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    string(payload, "address"), string(payload, "timezone")));
        } else {
            structureService.updateLocation(id, new OrganizationStructureService.UpdateLocationCommand(
                    requireString(payload, "code"), requireString(payload, "name"),
                    string(payload, "address"), string(payload, "timezone"),
                    MasterDataStatus.fromWire(string(payload, "status"))));
        }
    }

    // ---------------------------------------------------------------- payload 解析

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static String requireString(Map<String, Object> payload, String key) {
        String value = string(payload, key);
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("CHANGE_PAYLOAD_INVALID", "变更缺少字段：" + key);
        }
        return value;
    }

    private static UUID uuid(Map<String, Object> payload, String key) {
        String value = string(payload, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("CHANGE_PAYLOAD_INVALID", "字段不是合法 UUID：" + key);
        }
    }

    private static UUID requireUuid(Map<String, Object> payload, String key) {
        UUID value = uuid(payload, key);
        if (value == null) {
            throw ApiException.unprocessable("CHANGE_PAYLOAD_INVALID", "变更缺少字段：" + key);
        }
        return value;
    }

    private static Integer integer(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("CHANGE_PAYLOAD_INVALID", "字段不是整数：" + key);
        }
    }

    private static int intValue(Map<String, Object> payload, String key, int fallback) {
        Integer value = integer(payload, key);
        return value == null ? fallback : value;
    }

    private static LocalDate date(Map<String, Object> payload, String key) {
        String value = string(payload, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("CHANGE_PAYLOAD_INVALID", "字段不是合法日期：" + key);
        }
    }

    public record CreateChangeCommand(OrganizationChange.ChangeType changeType, UUID targetId,
                                      LocalDate effectiveDate, Map<String, Object> payload,
                                      String reason) {
    }

    public record ChangeResult(OrganizationChange change, boolean appliedImmediately) {
    }
}
