package com.acme.platform.organization;

import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 组织结构应用服务：法人、部门、职级、岗位、职位、成本中心、地点。
 *
 * <p>结构实体保存当前态；未来生效的调整走 {@link OrganizationChangeService}，
 * 由 Worker 在到期日原子切换。每次立即生效的写入都会留下不可变历史。
 */
@Service
public class OrganizationStructureService {

    private final TenantTransactions tenantTransactions;
    private final OrganizationStructureStore store;
    private final OrganizationCalendar calendar;
    private final OrganizationHistoryStore history;
    private final ImpactPreviewService previewService;
    private final ObjectMapper objectMapper;

    public OrganizationStructureService(TenantTransactions tenantTransactions,
                                        OrganizationStructureStore store,
                                        OrganizationCalendar calendar,
                                        OrganizationHistoryStore history,
                                        ImpactPreviewService previewService,
                                        ObjectMapper objectMapper) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
        this.calendar = calendar;
        this.history = history;
        this.previewService = previewService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 法人

    public List<LegalEntity> legalEntities() {
        return tenantTransactions.inTenant(ctx -> store.listLegalEntities());
    }

    public LegalEntity createLegalEntity(CreateLegalEntityCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            UUID id = UUID.randomUUID();
            try {
                store.insertLegalEntity(id, command.code(), command.name(), command.registrationNo());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("LEGAL_ENTITY_CODE_TAKEN", "法人编码已存在：" + command.code());
            }
            LegalEntity created = store.findLegalEntity(id).orElseThrow();
            record(created.tenantId(), null, "legal_entity", id, "create", created, null);
            return created;
        });
    }

    public LegalEntity updateLegalEntity(UUID id, UpdateLegalEntityCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            LegalEntity before = store.findLegalEntity(id).orElseThrow(() ->
                    ApiException.notFound("LEGAL_ENTITY_NOT_FOUND", "法人不存在：" + id));
            try {
                store.updateLegalEntity(id, command.code(), command.name(), command.registrationNo(),
                        command.status());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("LEGAL_ENTITY_CODE_TAKEN", "法人编码已存在：" + command.code());
            }
            LegalEntity after = store.findLegalEntity(id).orElseThrow();
            record(before.tenantId(), null, "legal_entity", id, "update", after, before);
            return after;
        });
    }

    // ---------------------------------------------------------------- 部门

    public List<OrgUnit> units() {
        return tenantTransactions.inTenant(ctx -> store.listUnits());
    }

    public List<OrgUnitNode> unitTree() {
        return tenantTransactions.inTenant(ctx -> buildTree(store.listUnits()));
    }

    public OrgUnit createUnit(CreateUnitCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            requireLegalEntity(command.legalEntityId());
            if (command.parentId() != null) {
                OrgUnit parent = store.findUnit(command.parentId()).orElseThrow(() ->
                        ApiException.unprocessable("ORG_UNIT_NOT_FOUND", "父部门不存在：" + command.parentId()));
                if (parent.status() == OrgUnit.UnitStatus.ARCHIVED) {
                    throw ApiException.unprocessable("ORG_UNIT_ARCHIVED", "已归档部门不能挂新子部门");
                }
            }
            UUID id = UUID.randomUUID();
            try {
                store.insertUnit(id, command.parentId(), command.legalEntityId(), command.code(),
                        command.name(), command.unitType(), command.sortOrder());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ORG_UNIT_CODE_TAKEN", "部门编码已存在：" + command.code());
            }
            OrgUnit created = store.findUnit(id).orElseThrow();
            record(created.tenantId(), null, "org_unit", id, "create", created, null);
            return created;
        });
    }

    public OrgUnit updateUnit(UUID id, UpdateUnitCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            OrgUnit before = store.findUnit(id).orElseThrow(() ->
                    ApiException.notFound("ORG_UNIT_NOT_FOUND", "部门不存在：" + id));
            store.updateUnit(id, command.name(), command.unitType(), command.sortOrder());
            OrgUnit after = store.findUnit(id).orElseThrow();
            record(before.tenantId(), null, "org_unit", id, "update", after, before);
            return after;
        });
    }

    public OrgUnit archiveUnit(UUID id) {
        return tenantTransactions.inTenant(ctx -> {
            OrgUnit before = store.findUnit(id).orElseThrow(() ->
                    ApiException.notFound("ORG_UNIT_NOT_FOUND", "部门不存在：" + id));
            if (store.hasActiveChildren(id)) {
                throw ApiException.unprocessable("ORG_UNIT_HAS_CHILDREN",
                        "部门仍有未归档的子部门，先处理子部门再归档");
            }
            if (store.hasActivePositions(id)) {
                throw ApiException.unprocessable("ORG_UNIT_HAS_ACTIVE_POSITIONS",
                        "部门仍有在职职位，先关闭职位再归档");
            }
            store.archiveUnit(id);
            OrgUnit after = store.findUnit(id).orElseThrow();
            record(before.tenantId(), null, "org_unit", id, "archive", after, before);
            return after;
        });
    }

    public ImpactPreview previewMove(UUID unitId, UUID targetParentId) {
        return previewService.previewMove(unitId, targetParentId);
    }

    /**
     * 已排期移动的到期应用：预览票据在创建变更单时已经校验并消费，
     * 这里只做环检测与落库，历史记录的 actor 是 system。
     */
    public OrgUnit applyScheduledMove(UUID unitId, UUID targetParentId, Integer sortOrder) {
        return tenantTransactions.inTenant(ctx -> {
            OrgUnit before = store.findUnit(unitId).orElseThrow(() ->
                    ApiException.notFound("ORG_UNIT_NOT_FOUND", "部门不存在：" + unitId));
            OrgTreeRules.ensureNoCycle(unitId, targetParentId,
                    candidate -> store.findParentId(candidate));
            store.moveUnit(unitId, targetParentId,
                    sortOrder == null ? before.sortOrder() : sortOrder);
            OrgUnit after = store.findUnit(unitId).orElseThrow();
            record(before.tenantId(), null, "org_unit", unitId, "move", after, before);
            return after;
        });
    }

    public OrgUnit moveUnit(UUID unitId, MoveUnitCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            OrgUnit before = store.findUnit(unitId).orElseThrow(() ->
                    ApiException.notFound("ORG_UNIT_NOT_FOUND", "部门不存在：" + unitId));
            if (command.targetParentId() != null) {
                OrgUnit parent = store.findUnit(command.targetParentId()).orElseThrow(() ->
                        ApiException.unprocessable("ORG_UNIT_NOT_FOUND",
                                "目标父部门不存在：" + command.targetParentId()));
                if (parent.status() == OrgUnit.UnitStatus.ARCHIVED) {
                    throw ApiException.unprocessable("ORG_UNIT_ARCHIVED", "已归档部门不能作为父部门");
                }
            }
            OrgTreeRules.ensureNoCycle(unitId, command.targetParentId(),
                    candidate -> store.findParentId(candidate));
            previewService.consumeForMove(command.previewId(), unitId, command.targetParentId());
            store.moveUnit(unitId, command.targetParentId(),
                    command.sortOrder() == null ? before.sortOrder() : command.sortOrder());
            OrgUnit after = store.findUnit(unitId).orElseThrow();
            record(before.tenantId(), null, "org_unit", unitId, "move", after, before);
            return after;
        });
    }

    // ---------------------------------------------------------------- 职级

    public List<JobLevel> jobLevels() {
        return tenantTransactions.inTenant(ctx -> store.listJobLevels());
    }

    public JobLevel createJobLevel(CreateJobLevelCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            UUID id = UUID.randomUUID();
            try {
                store.insertJobLevel(id, command.code(), command.name(), command.rank());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("JOB_LEVEL_CODE_TAKEN", "职级编码已存在：" + command.code());
            }
            JobLevel created = store.findJobLevel(id).orElseThrow();
            record(created.tenantId(), null, "job_level", id, "create", created, null);
            return created;
        });
    }

    public JobLevel updateJobLevel(UUID id, UpdateJobLevelCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            JobLevel before = store.findJobLevel(id).orElseThrow(() ->
                    ApiException.notFound("JOB_LEVEL_NOT_FOUND", "职级不存在：" + id));
            store.updateJobLevel(id, command.code(), command.name(), command.rank(), command.status());
            JobLevel after = store.findJobLevel(id).orElseThrow();
            record(before.tenantId(), null, "job_level", id, "update", after, before);
            return after;
        });
    }

    // ---------------------------------------------------------------- 岗位

    public List<Job> jobs() {
        return tenantTransactions.inTenant(ctx -> store.listJobs());
    }

    public Job createJob(CreateJobCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            if (command.defaultLevelId() != null) {
                store.findJobLevel(command.defaultLevelId()).orElseThrow(() ->
                        ApiException.unprocessable("JOB_LEVEL_NOT_FOUND",
                                "默认职级不存在：" + command.defaultLevelId()));
            }
            UUID id = UUID.randomUUID();
            try {
                store.insertJob(id, command.code(), command.name(), command.jobFamily(),
                        command.defaultLevelId());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("JOB_CODE_TAKEN", "岗位编码已存在：" + command.code());
            }
            Job created = store.findJob(id).orElseThrow();
            record(created.tenantId(), null, "job", id, "create", created, null);
            return created;
        });
    }

    public Job updateJob(UUID id, UpdateJobCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            Job before = store.findJob(id).orElseThrow(() ->
                    ApiException.notFound("JOB_NOT_FOUND", "岗位不存在：" + id));
            store.updateJob(id, command.code(), command.name(), command.jobFamily(),
                    command.defaultLevelId(), command.status());
            Job after = store.findJob(id).orElseThrow();
            record(before.tenantId(), null, "job", id, "update", after, before);
            return after;
        });
    }

    // ---------------------------------------------------------------- 职位

    public List<Position> positions(UUID orgUnitId) {
        return tenantTransactions.inTenant(ctx -> store.listPositions(orgUnitId));
    }

    public Position createPosition(CreatePositionCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            OrgUnit unit = store.findUnit(command.orgUnitId()).orElseThrow(() ->
                    ApiException.unprocessable("ORG_UNIT_NOT_FOUND",
                            "部门不存在：" + command.orgUnitId()));
            if (unit.status() == OrgUnit.UnitStatus.ARCHIVED) {
                throw ApiException.unprocessable("POSITION_UNIT_ARCHIVED", "不能给已归档部门新增职位");
            }
            store.findJob(command.jobId()).orElseThrow(() ->
                    ApiException.unprocessable("JOB_NOT_FOUND", "岗位不存在：" + command.jobId()));
            UUID id = UUID.randomUUID();
            try {
                store.insertPosition(id, command.orgUnitId(), command.jobId(), command.code(),
                        command.name(), command.headcountLimit());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("POSITION_CODE_TAKEN", "职位编码已存在：" + command.code());
            }
            Position created = store.findPosition(id).orElseThrow();
            record(created.tenantId(), null, "position", id, "create", created, null);
            return created;
        });
    }

    public Position updatePosition(UUID id, UpdatePositionCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            Position before = store.findPosition(id).orElseThrow(() ->
                    ApiException.notFound("POSITION_NOT_FOUND", "职位不存在：" + id));
            OrgUnit unit = store.findUnit(command.orgUnitId()).orElseThrow(() ->
                    ApiException.unprocessable("ORG_UNIT_NOT_FOUND",
                            "部门不存在：" + command.orgUnitId()));
            if (unit.status() == OrgUnit.UnitStatus.ARCHIVED) {
                throw ApiException.unprocessable("POSITION_UNIT_ARCHIVED", "不能把职位挂到已归档部门");
            }
            store.findJob(command.jobId()).orElseThrow(() ->
                    ApiException.unprocessable("JOB_NOT_FOUND", "岗位不存在：" + command.jobId()));
            store.updatePosition(id, command.orgUnitId(), command.jobId(), command.code(),
                    command.name(), command.headcountLimit(), command.status());
            Position after = store.findPosition(id).orElseThrow();
            record(before.tenantId(), null, "position", id, "update", after, before);
            return after;
        });
    }

    // ---------------------------------------------------------------- 成本中心

    public List<CostCenter> costCenters() {
        return tenantTransactions.inTenant(ctx -> store.listCostCenters());
    }

    public CostCenter createCostCenter(CreateCostCenterCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            requireLegalEntity(command.legalEntityId());
            UUID id = UUID.randomUUID();
            try {
                store.insertCostCenter(id, command.code(), command.name(), command.legalEntityId());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("COST_CENTER_CODE_TAKEN",
                        "成本中心编码已存在：" + command.code());
            }
            CostCenter created = store.findCostCenter(id).orElseThrow();
            record(created.tenantId(), null, "cost_center", id, "create", created, null);
            return created;
        });
    }

    public CostCenter updateCostCenter(UUID id, UpdateCostCenterCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            CostCenter before = store.findCostCenter(id).orElseThrow(() ->
                    ApiException.notFound("COST_CENTER_NOT_FOUND", "成本中心不存在：" + id));
            requireLegalEntity(command.legalEntityId());
            store.updateCostCenter(id, command.code(), command.name(), command.legalEntityId(),
                    command.status());
            CostCenter after = store.findCostCenter(id).orElseThrow();
            record(before.tenantId(), null, "cost_center", id, "update", after, before);
            return after;
        });
    }

    // ---------------------------------------------------------------- 地点

    public List<Location> locations() {
        return tenantTransactions.inTenant(ctx -> store.listLocations());
    }

    public Location createLocation(CreateLocationCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            UUID id = UUID.randomUUID();
            try {
                store.insertLocation(id, command.code(), command.name(), command.address(),
                        timezoneOrDefault(command.timezone()));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("LOCATION_CODE_TAKEN", "地点编码已存在：" + command.code());
            }
            Location created = store.findLocation(id).orElseThrow();
            record(created.tenantId(), null, "location", id, "create", created, null);
            return created;
        });
    }

    public Location updateLocation(UUID id, UpdateLocationCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            Location before = store.findLocation(id).orElseThrow(() ->
                    ApiException.notFound("LOCATION_NOT_FOUND", "地点不存在：" + id));
            store.updateLocation(id, command.code(), command.name(), command.address(),
                    timezoneOrDefault(command.timezone()), command.status());
            Location after = store.findLocation(id).orElseThrow();
            record(before.tenantId(), null, "location", id, "update", after, before);
            return after;
        });
    }

    // ---------------------------------------------------------------- 内部

    private void requireLegalEntity(UUID legalEntityId) {
        store.findLegalEntity(legalEntityId).orElseThrow(() ->
                ApiException.unprocessable("LEGAL_ENTITY_NOT_FOUND", "法人不存在：" + legalEntityId));
    }

    private static String timezoneOrDefault(String timezone) {
        return timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone;
    }

    private void record(UUID tenantId, UUID changeId, String entityType, UUID entityId,
                        String action, Object after, Object before) {
        String beforeJson = before == null ? null : objectMapper.writeValueAsString(before);
        String afterJson = after == null ? null : objectMapper.writeValueAsString(after);
        history.insert(changeId, entityType, entityId, action, calendar.today(tenantId),
                beforeJson, afterJson, actor());
    }

    static String actor() {
        return ActorContextHolder.current().map(actor -> actor.actorId().toString()).orElse("system");
    }

    private static List<OrgUnitNode> buildTree(List<OrgUnit> units) {
        Map<UUID, List<OrgUnit>> children = new LinkedHashMap<>();
        List<OrgUnit> roots = new ArrayList<>();
        for (OrgUnit unit : units) {
            if (unit.parentId() == null) {
                roots.add(unit);
            } else {
                children.computeIfAbsent(unit.parentId(), key -> new ArrayList<>()).add(unit);
            }
        }
        List<OrgUnitNode> nodes = new ArrayList<>();
        for (OrgUnit root : roots) {
            nodes.add(node(root, children, 0));
        }
        return nodes;
    }

    private static OrgUnitNode node(OrgUnit unit, Map<UUID, List<OrgUnit>> children, int depth) {
        if (depth > 64) {
            throw ApiException.unprocessable("ORG_UNIT_CYCLE", "部门树层级超过安全上限");
        }
        List<OrgUnitNode> childNodes = children.getOrDefault(unit.id(), List.of()).stream()
                .map(child -> node(child, children, depth + 1))
                .toList();
        return new OrgUnitNode(unit, childNodes);
    }

    /** 部门树节点：前端直接渲染，不需要自己再拼父子关系。 */
    public record OrgUnitNode(OrgUnit unit, List<OrgUnitNode> children) {
    }

    public record CreateLegalEntityCommand(String code, String name, String registrationNo) {
    }

    public record UpdateLegalEntityCommand(String code, String name, String registrationNo,
                                           MasterDataStatus status) {
    }

    public record CreateUnitCommand(UUID parentId, UUID legalEntityId, String code, String name,
                                    OrgUnit.UnitType unitType, int sortOrder) {
    }

    public record UpdateUnitCommand(String name, OrgUnit.UnitType unitType, int sortOrder) {
    }

    public record MoveUnitCommand(UUID targetParentId, UUID previewId, Integer sortOrder) {
    }

    public record CreateJobLevelCommand(String code, String name, int rank) {
    }

    public record UpdateJobLevelCommand(String code, String name, int rank, MasterDataStatus status) {
    }

    public record CreateJobCommand(String code, String name, String jobFamily, UUID defaultLevelId) {
    }

    public record UpdateJobCommand(String code, String name, String jobFamily, UUID defaultLevelId,
                                   MasterDataStatus status) {
    }

    public record CreatePositionCommand(UUID orgUnitId, UUID jobId, String code, String name,
                                        Integer headcountLimit) {
    }

    public record UpdatePositionCommand(UUID orgUnitId, UUID jobId, String code, String name,
                                        Integer headcountLimit, MasterDataStatus status) {
    }

    public record CreateCostCenterCommand(String code, String name, UUID legalEntityId) {
    }

    public record UpdateCostCenterCommand(String code, String name, UUID legalEntityId,
                                          MasterDataStatus status) {
    }

    public record CreateLocationCommand(String code, String name, String address, String timezone) {
    }

    public record UpdateLocationCommand(String code, String name, String address, String timezone,
                                        MasterDataStatus status) {
    }

    /** 供变更服务复用：当前部门快照，用于 before 状态。 */
    Optional<OrgUnit> findUnit(UUID id) {
        return store.findUnit(id);
    }
}
