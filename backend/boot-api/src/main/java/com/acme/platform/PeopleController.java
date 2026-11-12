package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.FieldAccess;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.organization.DirectoryScope;
import com.acme.platform.organization.Employment;
import com.acme.platform.organization.PeopleService;
import com.acme.platform.organization.Person;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自然人与雇佣关系。
 *
 * <p>读接口统一走授权决策：普通员工只看到本人与本部门目录，主管按直接下属/汇报链，
 * 老板按全租户；搜索、导出与批量读取复用同一决策与同一可见范围。
 * 写接口继续要求 {@code tenant.admin}，与既有管理员行为兼容。
 */
@RestController
@RequestMapping("/api/v1")
public class PeopleController {

    private final PeopleService peopleService;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;

    public PeopleController(PeopleService peopleService, AuthorizationGuard authorization,
                            MemberStore memberStore) {
        this.peopleService = peopleService;
        this.authorization = authorization;
        this.memberStore = memberStore;
    }

    public record CreatePersonRequest(String displayName, String primaryEmail, String employmentNo,
                                      LocalDate effectiveFrom, String jobTitle) {
    }

    public record AddEmploymentRequest(String employmentNo, LocalDate effectiveFrom, String jobTitle) {
    }

    public record EndEmploymentRequest(LocalDate effectiveTo) {
    }

    public record BatchReadRequest(List<UUID> ids) {
    }

    @GetMapping("/people")
    public ResponseEntity<?> list(@RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(required = false) String format) {
        if ("csv".equalsIgnoreCase(format)) {
            AccessDecision decision = authorization.require("directory.person.export",
                    "directory.person", null, Map.of());
            DirectoryScope scope = exportScope(decision);
            return csv(peopleService.export(scope, q));
        }
        AccessDecision decision = authorization.require("directory.person.read",
                "directory.person", null, Map.of());
        return ResponseEntity.ok(peopleService.directory(readScope(decision), q, limit));
    }

    @GetMapping("/people/{personId}")
    public PeopleService.DirectoryEntry get(@PathVariable UUID personId) {
        AccessDecision decision = authorization.require("directory.person.read",
                "directory.person", personId, Map.of("personId", personId));
        return peopleService.directoryEntry(personId, readScope(decision));
    }

    @PostMapping("/people/batch-read")
    public List<PeopleService.DirectoryEntry> batchRead(@RequestBody BatchReadRequest request) {
        AccessDecision decision = authorization.require("directory.person.read",
                "directory.person", null, Map.of());
        if (request.ids() == null || request.ids().isEmpty()) {
            return List.of();
        }
        return peopleService.batchRead(request.ids(), readScope(decision));
    }

    @PostMapping("/people")
    public Person create(@RequestBody CreatePersonRequest request) {
        authorization.require("tenant.admin", "tenant", null, Map.of());
        return peopleService.create(new PeopleService.CreatePersonCommand(request.displayName(),
                request.primaryEmail(), request.employmentNo(), request.effectiveFrom(),
                request.jobTitle()));
    }

    @GetMapping("/people/{personId}/employments")
    public List<Employment> employments(@PathVariable UUID personId) {
        AccessDecision decision = authorization.require("directory.employment.read",
                "directory.person", personId, Map.of("personId", personId));
        return peopleService.employments(personId, readScope(decision));
    }

    @PostMapping("/people/{personId}/employments")
    public Employment addEmployment(@PathVariable UUID personId,
                                    @RequestBody AddEmploymentRequest request) {
        authorization.require("tenant.admin", "tenant", null, Map.of());
        return peopleService.addEmployment(personId, new PeopleService.AddEmploymentCommand(
                request.employmentNo(), request.effectiveFrom(), request.jobTitle()));
    }

    @PatchMapping("/employments/{employmentId}/end")
    public Employment endEmployment(@PathVariable UUID employmentId,
                                    @RequestBody EndEmploymentRequest request) {
        authorization.require("tenant.admin", "tenant", null, Map.of());
        return peopleService.endEmployment(employmentId, request.effectiveTo());
    }

    private DirectoryScope readScope(AccessDecision decision) {
        return new DirectoryScope(decision.scope().tenantWide(), decision.scope().personIds(),
                decision.scope().orgUnitIds(), viewerPersonId(), viewAllowed(decision));
    }

    private DirectoryScope exportScope(AccessDecision decision) {
        FieldAccess email = decision.fieldRules().getOrDefault("primaryEmail", FieldAccess.none());
        return new DirectoryScope(decision.scope().tenantWide(), decision.scope().personIds(),
                decision.scope().orgUnitIds(), viewerPersonId(), email.export());
    }

    private boolean viewAllowed(AccessDecision decision) {
        return decision.fieldRules().getOrDefault("primaryEmail", FieldAccess.none()).view();
    }

    private UUID viewerPersonId() {
        UUID memberId = authorization.actor().memberId();
        if (memberId == null) {
            return null;
        }
        return memberStore.findById(memberId).map(member -> member.personId()).orElse(null);
    }

    private ResponseEntity<byte[]> csv(List<PeopleService.DirectoryEntry> entries) {
        StringBuilder builder = new StringBuilder("id,displayName,email,status\n");
        for (var entry : entries) {
            builder.append(entry.id()).append(',')
                    .append(escape(entry.displayName())).append(',')
                    .append(escape(entry.primaryEmail())).append(',')
                    .append(entry.status()).append('\n');
        }
        byte[] body = builder.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("people.csv", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
