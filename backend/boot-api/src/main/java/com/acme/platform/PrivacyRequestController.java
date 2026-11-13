package com.acme.platform;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.retention.DeletionRequest;
import com.acme.platform.platform.retention.DeletionRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户侧数据主体请求：员工/管理员发起，平台审批后执行。
 */
@RestController
@RequestMapping("/api/v1/privacy/requests")
class PrivacyRequestController {

    record SubmitRequest(
            @NotBlank @Pattern(regexp = "person|account") String subjectType,
            @NotNull UUID subjectId,
            @NotBlank @Size(min = 4, max = 500) String reason) {
    }

    record RequestView(
            UUID id,
            String subjectType,
            UUID subjectId,
            String status,
            String decisionCode,
            String decisionNote,
            OffsetDateTime requestedAt,
            OffsetDateTime decidedAt,
            OffsetDateTime executedAt) {

        static RequestView from(DeletionRequest request) {
            return new RequestView(request.id(), request.subjectType(), request.subjectId(), request.status(),
                    request.decisionCode(), request.decisionNote(), request.requestedAt(),
                    request.decidedAt(), request.executedAt());
        }
    }

    private final DeletionRequestService service;

    PrivacyRequestController(DeletionRequestService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RequestView submit(@Valid @RequestBody SubmitRequest body) {
        UUID tenantId = RequestContextHolder.current().tenantId();
        String actor = RequestContextHolder.current().subjectId();
        return RequestView.from(service.submit(tenantId, body.subjectType(), body.subjectId(),
                body.reason(), actor));
    }

    @GetMapping
    List<RequestView> list(@RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = RequestContextHolder.current().tenantId();
        return service.listByTenant(tenantId, limit).stream().map(RequestView::from).toList();
    }

    @GetMapping("/{id}")
    RequestView detail(@PathVariable UUID id) {
        DeletionRequest request = service.get(id);
        if (!request.tenantId().equals(RequestContextHolder.current().tenantId())) {
            throw ApiException.notFound("DELETION_REQUEST_NOT_FOUND", "删除请求不存在");
        }
        return RequestView.from(request);
    }

    @DeleteMapping("/{id}")
    RequestView cancel(@PathVariable UUID id) {
        UUID tenantId = RequestContextHolder.current().tenantId();
        return RequestView.from(service.cancel(id, tenantId, RequestContextHolder.current().subjectId()));
    }
}
