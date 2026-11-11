package com.acme.platform.worklog.device;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.worklog.policy.PolicyService;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 片段上传：先验设备签名与撤销状态，再校验策略版本，最后落最小化元数据。
 *
 * <p>员工未开始或已暂停时客户端根本不会入队，所以这里看到的任意一条记录都对应一次
 * 显式采集；服务端不再接受「补传开关」之类的请求字段。
 */
@Service
public class SegmentService {

    private static final Set<String> SOURCE_TYPES = Set.of("window", "manual", "voice");
    private static final int MAX_SUMMARY = 2000;

    private final DeviceAuthenticator authenticator;
    private final SegmentStore segmentStore;
    private final PolicyService policyService;
    private final TenantTransactions transactions;

    public SegmentService(DeviceAuthenticator authenticator, SegmentStore segmentStore,
                          PolicyService policyService, TenantTransactions transactions) {
        this.authenticator = authenticator;
        this.segmentStore = segmentStore;
        this.policyService = policyService;
        this.transactions = transactions;
    }

    public UploadResult upload(String method, String path, String timestamp, String signature,
                               String body, SegmentUploadRequest request) {
        validate(request);
        return transactions.inTenant(context -> {
            DeviceRecord device = authenticator.authenticate(
                    request.deviceId(), method, path, timestamp, signature, body);
            int currentVersion = policyService.currentVersion(context.tenantId());
            if (request.policyVersion() != currentVersion) {
                throw ApiException.conflict("POLICY_OUTDATED",
                        "本地策略版本 " + request.policyVersion() + " 已过期，当前版本 " + currentVersion);
            }
            boolean inserted = segmentStore.insert(context.tenantId(), device.id(),
                    request.clientSegmentId(), request.capturedFrom(), request.capturedTo(),
                    request.sourceType(), request.summary(), request.policyVersion());
            return new UploadResult(true, !inserted);
        });
    }

    private static void validate(SegmentUploadRequest request) {
        if (request == null || request.deviceId() == null) {
            throw ApiException.badRequest("DEVICE_ID_REQUIRED", "上传必须携带设备 ID");
        }
        if (request.clientSegmentId() == null) {
            throw ApiException.badRequest("CLIENT_SEGMENT_ID_REQUIRED", "上传必须携带客户端片段 ID");
        }
        if (request.capturedFrom() == null || request.capturedTo() == null
                || !request.capturedTo().isAfter(request.capturedFrom())) {
            throw ApiException.unprocessable("SEGMENT_TIME_INVALID", "片段结束时间必须晚于开始时间");
        }
        if (!SOURCE_TYPES.contains(request.sourceType())) {
            throw ApiException.unprocessable("SEGMENT_SOURCE_INVALID", "未知的片段来源类型");
        }
        if (request.summary() != null && request.summary().length() > MAX_SUMMARY) {
            throw ApiException.unprocessable("SEGMENT_SUMMARY_TOO_LONG", "摘要不能超过 " + MAX_SUMMARY + " 字符");
        }
    }

    public record UploadResult(boolean accepted, boolean duplicate) {
    }

    public record SegmentUploadRequest(
            UUID deviceId,
            UUID clientSegmentId,
            OffsetDateTime capturedFrom,
            OffsetDateTime capturedTo,
            String sourceType,
            String summary,
            int policyVersion) {
    }
}
