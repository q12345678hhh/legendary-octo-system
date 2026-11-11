package com.acme.platform;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.organization.Employment;
import com.acme.platform.organization.PeopleService;
import com.acme.platform.organization.Person;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自然人与雇佣关系。
 *
 * <p>读写都要求租户内已认证主体；人员档案的细粒度权限在 WP02-04 统一授权后收紧。
 * 这里刻意不创建登录账号：无账号员工只建 Person + Employment。
 */
@RestController
@RequestMapping("/api/v1")
public class PeopleController {

    private final PeopleService peopleService;
    private final IdentityGuard guard;

    public PeopleController(PeopleService peopleService, IdentityGuard guard) {
        this.peopleService = peopleService;
        this.guard = guard;
    }

    public record CreatePersonRequest(String displayName, String primaryEmail, String employmentNo,
                                      LocalDate effectiveFrom, String jobTitle) {
    }

    public record AddEmploymentRequest(String employmentNo, LocalDate effectiveFrom, String jobTitle) {
    }

    public record EndEmploymentRequest(LocalDate effectiveTo) {
    }

    @GetMapping("/people")
    public List<Person> list() {
        guard.requireUser();
        return peopleService.list();
    }

    @GetMapping("/people/{personId}")
    public Person get(@PathVariable UUID personId) {
        guard.requireUser();
        return peopleService.get(personId);
    }

    @PostMapping("/people")
    public Person create(@RequestBody CreatePersonRequest request) {
        guard.requireAdmin(false);
        return peopleService.create(new PeopleService.CreatePersonCommand(request.displayName(),
                request.primaryEmail(), request.employmentNo(), request.effectiveFrom(),
                request.jobTitle()));
    }

    @GetMapping("/people/{personId}/employments")
    public List<Employment> employments(@PathVariable UUID personId) {
        guard.requireUser();
        return peopleService.employments(personId);
    }

    @PostMapping("/people/{personId}/employments")
    public Employment addEmployment(@PathVariable UUID personId,
                                    @RequestBody AddEmploymentRequest request) {
        guard.requireAdmin(false);
        return peopleService.addEmployment(personId, new PeopleService.AddEmploymentCommand(
                request.employmentNo(), request.effectiveFrom(), request.jobTitle()));
    }

    @PatchMapping("/employments/{employmentId}/end")
    public Employment endEmployment(@PathVariable UUID employmentId,
                                    @RequestBody EndEmploymentRequest request) {
        guard.requireAdmin(false);
        return peopleService.endEmployment(employmentId, request.effectiveTo());
    }
}
