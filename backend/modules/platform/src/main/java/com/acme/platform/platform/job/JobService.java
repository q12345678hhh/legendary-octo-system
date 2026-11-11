package com.acme.platform.platform.job;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 作业应用服务：创建作业与投递事件同事务，取消只置标记。
 *
 * <p>作业本身不直接执行，执行权在 Worker：这样 API 进程重启不会丢任务，
 * 事件重投也不会重复创建作业（幂等过滤器保证同一请求只建一次）。
 */
@Service
public class JobService {

    static final String JOB_REQUESTED_EVENT = "platform.job.requested.v1";
    private static final Pattern JOB_TYPE = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;

    private final JobStore store;
    private final TenantTransactions transactions;
    private final OutboxEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final PlatformMetrics metrics;

    public JobService(JobStore store,
                      TenantTransactions transactions,
                      OutboxEventPublisher publisher,
                      ObjectMapper objectMapper,
                      PlatformMetrics metrics) {
        this.store = store;
        this.transactions = transactions;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public JobRecord create(String jobType, Map<String, Object> payload) {
        RequestContext context = requireIdentity();
        if (jobType == null || !JOB_TYPE.matcher(jobType).matches()) {
            throw ApiException.badRequest("JOB_TYPE_INVALID",
                    "jobType 必须是小写字母开头、含字母数字短横线且长度 3-64");
        }
        String payloadJson = toJson(payload == null ? Map.of() : payload);

        JobRecord created = transactions.inTenant(context, ctx -> {
            JobRecord job = store.insert(ctx.tenantId(), ctx.subjectId(), jobType, payloadJson,
                    ctx.traceId(), ctx.correlationId());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("job_id", job.id().toString());
            event.put("job_type", jobType);
            event.put("requested_by", Objects.requireNonNull(ctx.subjectId()));
            event.put("trace_id", ctx.traceId());
            event.put("correlation_id", ctx.correlationId());
            publisher.publish(JOB_REQUESTED_EVENT, event);
            return job;
        });

        metrics.jobSubmitted();
        metrics.jobState("queued");
        return created;
    }

    public JobRecord get(UUID id) {
        RequestContext context = requireIdentity();
        return transactions.inTenant(context, ctx -> store.find(id)
                .orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "作业不存在或不属于当前租户")));
    }

    public List<JobRecord> list(int limit) {
        RequestContext context = requireIdentity();
        int bounded = Math.max(1, Math.min(limit, 100));
        return transactions.inTenant(context, ctx -> store.list(bounded));
    }

    public JobRecord cancel(UUID id) {
        RequestContext context = requireIdentity();
        return transactions.inTenant(context, ctx -> {
            JobRecord job = store.find(id)
                    .orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "作业不存在或不属于当前租户"));
            if (job.terminal()) {
                throw ApiException.conflict("JOB_ALREADY_FINISHED",
                        "作业已结束，状态为 " + job.status());
            }
            store.requestCancel(id);
            return store.find(id).orElse(job);
        });
    }

    private String toJson(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw ApiException.badRequest("JOB_PAYLOAD_TOO_LARGE", "作业载荷不能超过 16KB");
            }
            return json;
        } catch (JacksonException e) {
            throw ApiException.badRequest("JOB_PAYLOAD_INVALID", "作业载荷无法序列化为 JSON");
        }
    }

    private static RequestContext requireIdentity() {
        RequestContext context = RequestContextHolder.current();
        if (context == null || context.tenantId() == null || context.subjectId() == null) {
            throw ApiException.badRequest("IDENTITY_REQUIRED",
                    "请求必须携带 X-Tenant-Id 且已完成认证");
        }
        return context;
    }
}
