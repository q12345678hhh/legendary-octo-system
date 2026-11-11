package com.acme.platform.organization;

import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 自然人与雇佣关系应用服务。
 *
 * <p>明确区分三种场景：
 * <ul>
 *   <li>无账号员工：只建 Person + Employment，不建账号；</li>
 *   <li>一人多雇佣：同一 Person 允许多条 active Employment；</li>
 *   <li>再雇佣：先结束旧 Employment，再新开一段，旧记录保留。</li>
 * </ul>
 */
@Service
public class PeopleService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern EMPLOYMENT_NO = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_NAME = 200;
    private static final int MAX_TITLE = 200;
    private static final int LIST_LIMIT = 200;

    private final TenantTransactions tenantTransactions;
    private final PersonStore personStore;
    private final EmploymentStore employmentStore;

    public PeopleService(TenantTransactions tenantTransactions,
                         PersonStore personStore,
                         EmploymentStore employmentStore) {
        this.tenantTransactions = tenantTransactions;
        this.personStore = personStore;
        this.employmentStore = employmentStore;
    }

    public Person create(CreatePersonCommand command) {
        validateName(command.displayName());
        String email = normalizeEmail(command.primaryEmail());
        validateEmployment(command.employmentNo(), command.effectiveFrom(), command.jobTitle());
        UUID personId = UUID.randomUUID();
        try {
            return tenantTransactions.inTenant(ctx -> {
                personStore.insert(personId, command.displayName().trim(), email);
                employmentStore.insert(UUID.randomUUID(), personId, command.employmentNo().trim(),
                        command.effectiveFrom(), trimToNull(command.jobTitle()));
                return personStore.find(personId).orElseThrow(() ->
                        new IllegalStateException("自然人写入后不可见：" + personId));
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMPLOYMENT_NUMBER_EXISTS",
                    "雇佣编号已存在：" + command.employmentNo());
        }
    }

    public Person get(UUID personId) {
        return tenantTransactions.inTenant(ctx -> personStore.find(personId).orElseThrow(() ->
                ApiException.notFound("PERSON_NOT_FOUND", "自然人不存在：" + personId)));
    }

    public List<Person> list() {
        return tenantTransactions.inTenant(ctx -> personStore.list(LIST_LIMIT));
    }

    public Employment addEmployment(UUID personId, AddEmploymentCommand command) {
        validateEmployment(command.employmentNo(), command.effectiveFrom(), command.jobTitle());
        try {
            return tenantTransactions.inTenant(ctx -> {
                if (!personStore.exists(personId)) {
                    throw ApiException.notFound("PERSON_NOT_FOUND", "自然人不存在：" + personId);
                }
                UUID employmentId = UUID.randomUUID();
                employmentStore.insert(employmentId, personId, command.employmentNo().trim(),
                        command.effectiveFrom(), trimToNull(command.jobTitle()));
                return employmentStore.find(employmentId).orElseThrow(() ->
                        new IllegalStateException("雇佣关系写入后不可见：" + employmentId));
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMPLOYMENT_NUMBER_EXISTS",
                    "雇佣编号已存在：" + command.employmentNo());
        }
    }

    public List<Employment> employments(UUID personId) {
        return tenantTransactions.inTenant(ctx -> {
            if (!personStore.exists(personId)) {
                throw ApiException.notFound("PERSON_NOT_FOUND", "自然人不存在：" + personId);
            }
            return employmentStore.listByPerson(personId);
        });
    }

    public Employment endEmployment(UUID employmentId, LocalDate effectiveTo) {
        if (effectiveTo == null) {
            throw ApiException.badRequest("EMPLOYMENT_END_REQUIRED", "必须提供雇佣结束日期");
        }
        return tenantTransactions.inTenant(ctx -> {
            Employment current = employmentStore.find(employmentId).orElseThrow(() ->
                    ApiException.notFound("EMPLOYMENT_NOT_FOUND", "雇佣关系不存在：" + employmentId));
            if (current.status() == EmploymentStatus.ENDED) {
                throw ApiException.conflict("EMPLOYMENT_ALREADY_ENDED", "该雇佣关系已经结束");
            }
            if (!effectiveTo.isAfter(current.effectiveFrom())) {
                throw ApiException.unprocessable("EMPLOYMENT_PERIOD_INVALID",
                        "结束日期必须晚于生效日期 " + current.effectiveFrom());
            }
            employmentStore.end(employmentId, effectiveTo);
            return employmentStore.find(employmentId).orElseThrow();
        });
    }

    public String currentActor() {
        return ActorContextHolder.current().map(actor -> actor.actorId().toString()).orElse("system");
    }

    private void validateName(String displayName) {
        if (displayName == null || displayName.isBlank()
                || displayName.trim().length() > MAX_NAME) {
            throw ApiException.unprocessable("PERSON_NAME_INVALID", "姓名长度必须在 1 到 200 之间");
        }
    }

    private void validateEmployment(String employmentNo, LocalDate effectiveFrom, String jobTitle) {
        if (employmentNo == null || !EMPLOYMENT_NO.matcher(employmentNo.trim()).matches()) {
            throw ApiException.unprocessable("EMPLOYMENT_NUMBER_INVALID",
                    "雇佣编号只能是 1-64 位字母、数字、点、下划线或连字符");
        }
        if (effectiveFrom == null) {
            throw ApiException.unprocessable("EMPLOYMENT_START_REQUIRED", "必须提供雇佣生效日期");
        }
        if (jobTitle != null && jobTitle.trim().length() > MAX_TITLE) {
            throw ApiException.unprocessable("EMPLOYMENT_TITLE_INVALID", "岗位名称不能超过 200 字");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String value = email.trim().toLowerCase();
        if (value.length() > 320 || !EMAIL.matcher(value).matches()) {
            throw ApiException.unprocessable("PERSON_EMAIL_INVALID", "联系邮箱格式不正确");
        }
        return value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreatePersonCommand(String displayName,
                                      String primaryEmail,
                                      String employmentNo,
                                      LocalDate effectiveFrom,
                                      String jobTitle) {
    }

    public record AddEmploymentCommand(String employmentNo,
                                       LocalDate effectiveFrom,
                                       String jobTitle) {
    }
}
