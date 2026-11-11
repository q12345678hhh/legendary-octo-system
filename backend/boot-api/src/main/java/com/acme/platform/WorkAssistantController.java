package com.acme.platform;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.worklog.device.DeviceService;
import com.acme.platform.worklog.device.SegmentService;
import com.acme.platform.worklog.policy.PolicyService;
import com.acme.platform.worklog.policy.SignedPolicy;
import com.acme.platform.worklog.update.UpdateManifest;
import com.acme.platform.worklog.update.UpdateManifestService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工作助手设备链路。
 *
 * <p>刻意没有「开始采集」端点：采集状态只能由员工在本机托盘操作，
 * 管理员能做的只有追加排除项与撤销设备。
 */
@RestController
@RequestMapping("/api/v1/work-assistant")
public class WorkAssistantController {

    private static final String TIMESTAMP_HEADER = "X-Device-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Device-Signature";
    private static final Set<String> POLICY_FIELDS = Set.of("additional_exclusions");

    private final DeviceService deviceService;
    private final SegmentService segmentService;
    private final PolicyService policyService;
    private final UpdateManifestService updateManifestService;
    private final ObjectMapper objectMapper;

    public WorkAssistantController(DeviceService deviceService, SegmentService segmentService,
                                   PolicyService policyService,
                                   UpdateManifestService updateManifestService,
                                   ObjectMapper objectMapper) {
        this.deviceService = deviceService;
        this.segmentService = segmentService;
        this.policyService = policyService;
        this.updateManifestService = updateManifestService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/devices/register")
    public DeviceService.Registration register(@RequestBody RegisterDeviceRequest request) {
        return deviceService.register(request.deviceId(), request.deviceName(),
                request.publicKeySpki(), request.appVersion());
    }

    @PostMapping("/devices/{deviceId}/heartbeat")
    public DeviceService.HeartbeatResult heartbeat(
            @PathVariable UUID deviceId,
            @RequestHeader(TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(SIGNATURE_HEADER) String signature,
            @RequestBody String body,
            HttpServletRequest request) {
        return deviceService.heartbeat(deviceId, request.getMethod(), request.getRequestURI(),
                timestamp, signature, body, parse(body, DeviceService.HeartbeatRequest.class));
    }

    @GetMapping("/devices/{deviceId}/policy")
    public SignedPolicy policy(
            @PathVariable UUID deviceId,
            @RequestHeader(TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(SIGNATURE_HEADER) String signature,
            HttpServletRequest request) {
        return deviceService.policy(deviceId, request.getMethod(), request.getRequestURI(),
                timestamp, signature);
    }

    @PostMapping("/devices/{deviceId}/revoke")
    public DeviceService.RevokeResult revoke(@PathVariable UUID deviceId) {
        String operator = RequestContextHolder.find()
                .map(com.acme.platform.platform.RequestContext::subjectId)
                .orElse(null);
        if (operator == null) {
            throw ApiException.badRequest("SUBJECT_REQUIRED", "撤销设备必须已认证");
        }
        deviceService.revoke(deviceId, operator);
        return new DeviceService.RevokeResult("revoked");
    }

    @PostMapping("/segments")
    public SegmentService.UploadResult upload(
            @RequestHeader(TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(SIGNATURE_HEADER) String signature,
            @RequestHeader("X-Device-Id") String deviceId,
            @RequestBody String body,
            HttpServletRequest request) {
        SegmentService.SegmentUploadRequest upload =
                parse(body, SegmentService.SegmentUploadRequest.class);
        if (!deviceId.equals(String.valueOf(upload.deviceId()))) {
            throw ApiException.forbidden("DEVICE_ID_MISMATCH", "请求头与请求体的设备 ID 不一致");
        }
        return segmentService.upload(request.getMethod(), request.getRequestURI(),
                timestamp, signature, body, upload);
    }

    @GetMapping("/update-manifest")
    public UpdateManifest updateManifest() {
        UpdateManifest manifest = updateManifestService.current();
        if (manifest == null) {
            throw new ApiException(503, "UPDATE_MANIFEST_UNAVAILABLE", "平台未配置更新清单与签名密钥");
        }
        return manifest;
    }

    /** 只接受追加排除项；出现任何未知字段（例如删除列表或采集开关）直接 422。 */
    @PutMapping("/policies/current")
    public SignedPolicy appendPolicy(@RequestBody JsonNode body) {
        for (String field : body.propertyNames()) {
            if (!POLICY_FIELDS.contains(field)) {
                throw ApiException.unprocessable("POLICY_UNKNOWN_FIELD",
                        "策略更新只接受 additional_exclusions，拒绝字段: " + field);
            }
        }
        JsonNode additions = body.path("additional_exclusions");
        if (!additions.isArray()) {
            throw ApiException.unprocessable("POLICY_ADDITIONS_REQUIRED",
                    "additional_exclusions 必须是数组");
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode node : additions) {
            values.add(node.isString() ? node.asString() : null);
        }
        String operator = RequestContextHolder.find()
                .map(com.acme.platform.platform.RequestContext::subjectId)
                .orElse(null);
        if (operator == null) {
            throw ApiException.badRequest("SUBJECT_REQUIRED", "策略更新必须已认证");
        }
        return policyService.append(values, operator);
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("REQUEST_BODY_INVALID", "请求体不是合法 JSON");
        }
    }

    public record RegisterDeviceRequest(UUID deviceId, String deviceName,
                                        String publicKeySpki, String appVersion) {
    }
}
