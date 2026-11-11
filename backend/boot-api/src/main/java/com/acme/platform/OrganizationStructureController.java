package com.acme.platform;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.organization.CostCenter;
import com.acme.platform.organization.ImpactPreview;
import com.acme.platform.organization.Job;
import com.acme.platform.organization.JobLevel;
import com.acme.platform.organization.LegalEntity;
import com.acme.platform.organization.Location;
import com.acme.platform.organization.OrgUnit;
import com.acme.platform.organization.OrganizationStructureService;
import com.acme.platform.organization.Position;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织结构：法人、部门树、职级、岗位、职位、成本中心、地点。
 *
 * <p>结构写入只影响当前态；未来生效的调整走 {@code /organization/changes}。
 * 部门移动必须先生成预览、再带 previewId 提交，服务端会复算所有影响计数。
 */
@RestController
@RequestMapping("/api/v1")
public class OrganizationStructureController {

    private final OrganizationStructureService structureService;
    private final IdentityGuard guard;

    public OrganizationStructureController(OrganizationStructureService structureService,
                                           IdentityGuard guard) {
        this.structureService = structureService;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- 法人

    @GetMapping("/legal-entities")
    public List<LegalEntity> legalEntities() {
        guard.requireUser();
        return structureService.legalEntities();
    }

    @PostMapping("/legal-entities")
    public LegalEntity createLegalEntity(@RequestBody LegalEntityRequest request) {
        guard.requireAdmin(false);
        return structureService.createLegalEntity(new OrganizationStructureService.CreateLegalEntityCommand(
                request.code(), request.name(), request.registrationNo()));
    }

    @PutMapping("/legal-entities/{legalEntityId}")
    public LegalEntity updateLegalEntity(@PathVariable UUID legalEntityId,
                                         @RequestBody LegalEntityRequest request) {
        guard.requireAdmin(false);
        return structureService.updateLegalEntity(legalEntityId,
                new OrganizationStructureService.UpdateLegalEntityCommand(request.code(), request.name(),
                        request.registrationNo(),
                        com.acme.platform.organization.MasterDataStatus.fromWire(request.status())));
    }

    // ---------------------------------------------------------------- 部门

    @GetMapping("/org-units")
    public List<OrgUnit> units() {
        guard.requireUser();
        return structureService.units();
    }

    @GetMapping("/org-units/tree")
    public List<OrganizationStructureService.OrgUnitNode> tree() {
        guard.requireUser();
        return structureService.unitTree();
    }

    @PostMapping("/org-units")
    public OrgUnit createUnit(@RequestBody UnitRequest request) {
        guard.requireAdmin(false);
        return structureService.createUnit(new OrganizationStructureService.CreateUnitCommand(
                request.parentId(), request.legalEntityId(), request.code(), request.name(),
                OrgUnit.UnitType.fromWire(request.unitType()),
                request.sortOrder() == null ? 0 : request.sortOrder()));
    }

    @PutMapping("/org-units/{unitId}")
    public OrgUnit updateUnit(@PathVariable UUID unitId, @RequestBody UnitRequest request) {
        guard.requireAdmin(false);
        return structureService.updateUnit(unitId, new OrganizationStructureService.UpdateUnitCommand(
                request.name(), OrgUnit.UnitType.fromWire(request.unitType()),
                request.sortOrder() == null ? 0 : request.sortOrder()));
    }

    @PostMapping("/org-units/{unitId}/archive")
    public OrgUnit archiveUnit(@PathVariable UUID unitId) {
        guard.requireAdmin(false);
        return structureService.archiveUnit(unitId);
    }

    @PostMapping("/org-units/{unitId}/move-previews")
    public ImpactPreview previewMove(@PathVariable UUID unitId,
                                     @RequestBody MovePreviewRequest request) {
        guard.requireAdmin(false);
        return structureService.previewMove(unitId, request.targetParentId());
    }

    @PostMapping("/org-units/{unitId}/move")
    public OrgUnit moveUnit(@PathVariable UUID unitId, @RequestBody MoveRequest request) {
        guard.requireAdmin(false);
        return structureService.moveUnit(unitId, new OrganizationStructureService.MoveUnitCommand(
                request.targetParentId(), request.previewId(), request.sortOrder()));
    }

    // ---------------------------------------------------------------- 职级

    @GetMapping("/job-levels")
    public List<JobLevel> jobLevels() {
        guard.requireUser();
        return structureService.jobLevels();
    }

    @PostMapping("/job-levels")
    public JobLevel createJobLevel(@RequestBody JobLevelRequest request) {
        guard.requireAdmin(false);
        return structureService.createJobLevel(new OrganizationStructureService.CreateJobLevelCommand(
                request.code(), request.name(), request.rank() == null ? 1 : request.rank()));
    }

    @PutMapping("/job-levels/{jobLevelId}")
    public JobLevel updateJobLevel(@PathVariable UUID jobLevelId, @RequestBody JobLevelRequest request) {
        guard.requireAdmin(false);
        return structureService.updateJobLevel(jobLevelId,
                new OrganizationStructureService.UpdateJobLevelCommand(request.code(), request.name(),
                        request.rank() == null ? 1 : request.rank(),
                        com.acme.platform.organization.MasterDataStatus.fromWire(request.status())));
    }

    // ---------------------------------------------------------------- 岗位

    @GetMapping("/jobs")
    public List<Job> jobs() {
        guard.requireUser();
        return structureService.jobs();
    }

    @PostMapping("/jobs")
    public Job createJob(@RequestBody JobRequest request) {
        guard.requireAdmin(false);
        return structureService.createJob(new OrganizationStructureService.CreateJobCommand(
                request.code(), request.name(), request.jobFamily(), request.defaultLevelId()));
    }

    @PutMapping("/jobs/{jobId}")
    public Job updateJob(@PathVariable UUID jobId, @RequestBody JobRequest request) {
        guard.requireAdmin(false);
        return structureService.updateJob(jobId, new OrganizationStructureService.UpdateJobCommand(
                request.code(), request.name(), request.jobFamily(), request.defaultLevelId(),
                com.acme.platform.organization.MasterDataStatus.fromWire(request.status())));
    }

    // ---------------------------------------------------------------- 职位

    @GetMapping("/positions")
    public List<Position> positions(@RequestParam(required = false) UUID orgUnitId) {
        guard.requireUser();
        return structureService.positions(orgUnitId);
    }

    @PostMapping("/positions")
    public Position createPosition(@RequestBody PositionRequest request) {
        guard.requireAdmin(false);
        return structureService.createPosition(new OrganizationStructureService.CreatePositionCommand(
                request.orgUnitId(), request.jobId(), request.code(), request.name(),
                request.headcountLimit()));
    }

    @PutMapping("/positions/{positionId}")
    public Position updatePosition(@PathVariable UUID positionId, @RequestBody PositionRequest request) {
        guard.requireAdmin(false);
        return structureService.updatePosition(positionId,
                new OrganizationStructureService.UpdatePositionCommand(request.orgUnitId(),
                        request.jobId(), request.code(), request.name(), request.headcountLimit(),
                        com.acme.platform.organization.MasterDataStatus.fromWire(request.status())));
    }

    // ---------------------------------------------------------------- 成本中心

    @GetMapping("/cost-centers")
    public List<CostCenter> costCenters() {
        guard.requireUser();
        return structureService.costCenters();
    }

    @PostMapping("/cost-centers")
    public CostCenter createCostCenter(@RequestBody CostCenterRequest request) {
        guard.requireAdmin(false);
        return structureService.createCostCenter(new OrganizationStructureService.CreateCostCenterCommand(
                request.code(), request.name(), request.legalEntityId()));
    }

    @PutMapping("/cost-centers/{costCenterId}")
    public CostCenter updateCostCenter(@PathVariable UUID costCenterId,
                                       @RequestBody CostCenterRequest request) {
        guard.requireAdmin(false);
        return structureService.updateCostCenter(costCenterId,
                new OrganizationStructureService.UpdateCostCenterCommand(request.code(), request.name(),
                        request.legalEntityId(),
                        com.acme.platform.organization.MasterDataStatus.fromWire(request.status())));
    }

    // ---------------------------------------------------------------- 地点

    @GetMapping("/locations")
    public List<Location> locations() {
        guard.requireUser();
        return structureService.locations();
    }

    @PostMapping("/locations")
    public Location createLocation(@RequestBody LocationRequest request) {
        guard.requireAdmin(false);
        return structureService.createLocation(new OrganizationStructureService.CreateLocationCommand(
                request.code(), request.name(), request.address(), request.timezone()));
    }

    @PutMapping("/locations/{locationId}")
    public Location updateLocation(@PathVariable UUID locationId, @RequestBody LocationRequest request) {
        guard.requireAdmin(false);
        return structureService.updateLocation(locationId,
                new OrganizationStructureService.UpdateLocationCommand(request.code(), request.name(),
                        request.address(), request.timezone(),
                        com.acme.platform.organization.MasterDataStatus.fromWire(request.status())));
    }

    // ---------------------------------------------------------------- 请求体

    public record LegalEntityRequest(String code, String name, String registrationNo, String status) {
    }

    public record UnitRequest(UUID parentId, UUID legalEntityId, String code, String name,
                              String unitType, Integer sortOrder) {
    }

    public record MovePreviewRequest(UUID targetParentId) {
    }

    public record MoveRequest(UUID targetParentId, UUID previewId, Integer sortOrder) {
    }

    public record JobLevelRequest(String code, String name, Integer rank, String status) {
    }

    public record JobRequest(String code, String name, String jobFamily, UUID defaultLevelId,
                             String status) {
    }

    public record PositionRequest(UUID orgUnitId, UUID jobId, String code, String name,
                                  Integer headcountLimit, String status) {
    }

    public record CostCenterRequest(String code, String name, UUID legalEntityId, String status) {
    }

    public record LocationRequest(String code, String name, String address, String timezone,
                                  String status) {
    }
}
