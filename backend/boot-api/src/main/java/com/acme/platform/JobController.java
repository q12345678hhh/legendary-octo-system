package com.acme.platform;

import com.acme.platform.platform.job.JobRecord;
import com.acme.platform.platform.job.JobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 异步作业接口：创建、查询、取消。
 *
 * <p>创建请求带 Idempotency-Key 时，重复提交只会得到一个作业；这是本接口的客户端契约，
 * 前端变更类请求默认自动生成该头。
 */
@RestController
@RequestMapping("/api/v1/system/jobs")
class JobController {

    record CreateJobRequest(
            @NotBlank @Size(max = 64) String jobType,
            Map<String, Object> payload) {
    }

    record JobResponse(
            UUID jobId,
            String jobType,
            String status,
            int progress,
            boolean cancelRequested,
            JsonNode payload,
            JsonNode result,
            String lastError,
            String traceId,
            String correlationId,
            OffsetDateTime requestedAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {

        static JobResponse from(JobRecord job, ObjectMapper mapper) {
            return new JobResponse(job.id(), job.jobType(), job.status(), job.progress(),
                    job.cancelRequested(), read(job.payload(), mapper), read(job.result(), mapper),
                    job.lastError(), job.traceId(), job.correlationId(), job.requestedAt(),
                    job.startedAt(), job.finishedAt());
        }

        private static JsonNode read(String json, ObjectMapper mapper) {
            return json == null || json.isBlank() ? null : mapper.readTree(json);
        }
    }

    private final JobService service;
    private final ObjectMapper objectMapper;

    JobController(JobService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    JobResponse create(@Valid @RequestBody CreateJobRequest request) {
        return JobResponse.from(service.create(request.jobType(), request.payload()), objectMapper);
    }

    @GetMapping
    List<JobResponse> list(@RequestParam(defaultValue = "20") int limit) {
        return service.list(limit).stream().map(job -> JobResponse.from(job, objectMapper)).toList();
    }

    @GetMapping("/{jobId}")
    JobResponse get(@PathVariable UUID jobId) {
        return JobResponse.from(service.get(jobId), objectMapper);
    }

    @PostMapping("/{jobId}/cancel")
    JobResponse cancel(@PathVariable UUID jobId) {
        return JobResponse.from(service.cancel(jobId), objectMapper);
    }
}
