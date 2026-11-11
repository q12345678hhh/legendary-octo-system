package com.acme.platform;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.organization.OrganizationChange;
import com.acme.platform.organization.OrganizationChangeService;
import com.acme.platform.organization.OrganizationHistoryEntry;
import com.acme.platform.platform.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织变更单与历史快照。
 *
 * <p>结构接口只改当前态；未来生效的调整一律先建变更单，到期由 Worker 在租户上下文里应用。
 * 生效日期已到的变更在创建时立即应用，并返回 {@code appliedImmediately=true}。
 */
@RestController
@RequestMapping("/api/v1")
public class OrganizationChangeController {

    private static final int DEFAULT_DUE_BATCH = 50;
    private static final int MAX_DUE_BATCH = 200;

    private final OrganizationChangeService changeService;
    private final IdentityGuard guard;

    public OrganizationChangeController(OrganizationChangeService changeService, IdentityGuard guard) {
        this.changeService = changeService;
        this.guard = guard;
    }

    @GetMapping("/organization/changes")
    public List<OrganizationChange> list(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) UUID targetId) {
        guard.requireUser();
        return changeService.list(status == null || status.isBlank()
                ? null : OrganizationChange.ChangeStatus.fromWire(status), targetId);
    }

    @PostMapping("/organization/changes")
    public OrganizationChangeService.ChangeResult create(@RequestBody CreateChangeRequest request) {
        guard.requireAdmin(false);
        return changeService.create(new OrganizationChangeService.CreateChangeCommand(
                OrganizationChange.ChangeType.fromWire(requireType(request.changeType())),
                request.targetId(), request.effectiveDate(), request.payload(), request.reason()));
    }

    @GetMapping("/organization/changes/{changeId}")
    public OrganizationChange get(@PathVariable UUID changeId) {
        guard.requireUser();
        return changeService.get(changeId);
    }

    @PostMapping("/organization/changes/{changeId}/cancel")
    public OrganizationChange cancel(@PathVariable UUID changeId) {
        guard.requireAdmin(false);
        return changeService.cancel(changeId);
    }

    /** 手动触发一次到期扫描；Worker 在正常运行时也会周期性调用同一逻辑。 */
    @PostMapping("/organization/changes/apply-due")
    public ApplyDueResult applyDue(@RequestBody(required = false) ApplyDueRequest request) {
        guard.requireAdmin(false);
        int batch = request == null || request.batchSize() == null
                ? DEFAULT_DUE_BATCH : request.batchSize();
        if (batch < 1 || batch > MAX_DUE_BATCH) {
            throw ApiException.unprocessable("BATCH_SIZE_INVALID",
                    "batchSize 必须在 1 到 " + MAX_DUE_BATCH + " 之间");
        }
        return new ApplyDueResult(changeService.applyDue(batch));
    }

    @GetMapping("/organization/history")
    public List<OrganizationHistoryEntry> history(@RequestParam(required = false) String entityType,
                                                  @RequestParam(required = false) UUID entityId) {
        guard.requireUser();
        return changeService.history(entityType, entityId);
    }

    private static String requireType(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("CHANGE_TYPE_REQUIRED", "必须提供 changeType");
        }
        return value;
    }

    public record CreateChangeRequest(String changeType, UUID targetId, LocalDate effectiveDate,
                                      Map<String, Object> payload, String reason) {
    }

    public record ApplyDueRequest(Integer batchSize) {
    }

    public record ApplyDueResult(int applied) {
    }
}
