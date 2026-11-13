using System.Net;
using System.Text;
using System.Text.Json;
using WorkAssistant.Core;

namespace WorkAssistant.Device;

/// <summary>服务端 Problem Details 的客户端视图。</summary>
public sealed class DeviceApiException : Exception
{
    public DeviceApiException(int statusCode, string code, string detail) : base(detail)
    {
        StatusCode = statusCode;
        Code = code;
    }

    public int StatusCode { get; }

    public string Code { get; }

    public bool IsRevoked => StatusCode == 403 && Code == "DEVICE_REVOKED";
}

public sealed record RegistrationInfo(
    string DeviceId,
    string Status,
    string PolicyJson,
    string PolicyPublicKeySpki,
    string PolicyKeyId,
    string? UpdatePublicKeySpki,
    string? UpdateKeyId);

public sealed record UploadResult(bool Accepted, bool Duplicate);

public sealed record HeartbeatResult(string Status, string MinSupportedVersion, bool UpdateRequired);

/// <summary>
/// 设备 API 客户端。所有读取/上传请求都用设备私钥签名；
/// 恢复出厂或换机后私钥不在，只能重新注册。
/// </summary>
public sealed class DeviceApiClient
{
    private const string BasePath = "/api/v1/work-assistant";

    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly HttpClient http;
    private readonly string tenantId;
    private readonly string? subjectId;
    private readonly string? sessionToken;
    private readonly DeviceIdentity identity;

    public DeviceApiClient(HttpClient http, string tenantId, DeviceIdentity identity, string? subjectId = null,
        string? sessionToken = null)
    {
        this.http = http;
        this.tenantId = tenantId;
        this.subjectId = subjectId;
        // 联调/取证脚本通过环境变量注入会话，避免把令牌写进命令行
        this.sessionToken = string.IsNullOrWhiteSpace(sessionToken)
            ? Environment.GetEnvironmentVariable("PLATFORM_API_SESSION")
            : sessionToken;
        this.identity = identity;
    }

    public async Task<RegistrationInfo> RegisterAsync(string deviceName, string appVersion,
        CancellationToken cancellationToken = default)
    {
        var body = JsonSerializer.Serialize(new
        {
            deviceId = identity.DeviceId,
            deviceName,
            publicKeySpki = identity.PublicKeySpki,
            appVersion,
        }, Json);
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BasePath}/devices/register");
        AddTenantHeaders(request);
        request.Content = JsonBody(body);
        var json = await SendAsync(request, cancellationToken);

        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        return new RegistrationInfo(
            root.GetProperty("deviceId").GetString()!,
            root.GetProperty("status").GetString()!,
            root.GetProperty("policy").GetRawText(),
            root.GetProperty("policyPublicKeySpki").GetString()!,
            root.GetProperty("policyKeyId").GetString()!,
            root.TryGetProperty("updatePublicKeySpki", out var updateKey) && updateKey.ValueKind == JsonValueKind.String
                ? updateKey.GetString() : null,
            root.TryGetProperty("updateKeyId", out var updateKeyId) && updateKeyId.ValueKind == JsonValueKind.String
                ? updateKeyId.GetString() : null);
    }

    public async Task<HeartbeatResult> HeartbeatAsync(string appVersion, int policyVersion,
        CancellationToken cancellationToken = default)
    {
        var body = JsonSerializer.Serialize(new { appVersion, policyVersion }, Json);
        var path = $"{BasePath}/devices/{identity.DeviceId}/heartbeat";
        var json = await SendSignedAsync(HttpMethod.Post, path, body, cancellationToken);
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        return new HeartbeatResult(
            root.GetProperty("status").GetString()!,
            root.GetProperty("minSupportedVersion").GetString()!,
            root.GetProperty("updateRequired").GetBoolean());
    }

    public async Task<string> GetPolicyJsonAsync(CancellationToken cancellationToken = default)
    {
        var path = $"{BasePath}/devices/{identity.DeviceId}/policy";
        return await SendSignedAsync(HttpMethod.Get, path, string.Empty, cancellationToken);
    }

    public async Task<UploadResult> UploadAsync(SegmentDraft draft, CancellationToken cancellationToken = default)
    {
        var body = JsonSerializer.Serialize(new
        {
            deviceId = identity.DeviceId,
            clientSegmentId = draft.ClientSegmentId,
            capturedFrom = draft.CapturedFrom.ToString("O"),
            capturedTo = draft.CapturedTo.ToString("O"),
            sourceType = draft.SourceType,
            summary = draft.Summary,
            policyVersion = draft.PolicyVersion,
        }, Json);
        var json = await SendSignedAsync(HttpMethod.Post, $"{BasePath}/segments", body, cancellationToken);
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        return new UploadResult(
            root.GetProperty("accepted").GetBoolean(),
            root.GetProperty("duplicate").GetBoolean());
    }

    /// <summary>骨架阶段的撤销入口只给取证脚本使用；客户端本身没有调用它的路径。</summary>
    public async Task RevokeAsync(CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post,
            $"{BasePath}/devices/{identity.DeviceId}/revoke");
        AddTenantHeaders(request);
        await SendAsync(request, cancellationToken);
    }

    private async Task<string> SendSignedAsync(HttpMethod method, string path, string body,
        CancellationToken cancellationToken)
    {
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(System.Globalization.CultureInfo.InvariantCulture);
        var canonical = SigningCanonical.Request(method.Method, path, timestamp, body);
        using var request = new HttpRequestMessage(method, path);
        AddTenantHeaders(request);
        request.Headers.Add("X-Device-Id", identity.DeviceId);
        request.Headers.Add("X-Device-Timestamp", timestamp);
        request.Headers.Add("X-Device-Signature", identity.Sign(canonical));
        if (method != HttpMethod.Get)
        {
            request.Content = JsonBody(body);
        }

        return await SendAsync(request, cancellationToken);
    }

    private async Task<string> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        using var response = await http.SendAsync(request, cancellationToken);
        var content = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw ToException(response.StatusCode, content);
        }

        return content;
    }

    private void AddTenantHeaders(HttpRequestMessage request)
    {
        request.Headers.Add("X-Tenant-Id", tenantId);
        if (!string.IsNullOrWhiteSpace(subjectId))
        {
            request.Headers.Add("X-Subject-Id", subjectId);
        }

        if (!string.IsNullOrWhiteSpace(sessionToken))
        {
            request.Headers.TryAddWithoutValidation("Cookie",
                $"plt_session={sessionToken}; plt_csrf={sessionToken}");
            request.Headers.TryAddWithoutValidation("X-CSRF-Token", sessionToken);
        }
    }

    private static HttpContent JsonBody(string body)
    {
        return new StringContent(body, Encoding.UTF8, "application/json");
    }

    private static DeviceApiException ToException(HttpStatusCode status, string content)
    {
        string code = "HTTP_" + (int)status;
        string detail = content;
        try
        {
            using var document = JsonDocument.Parse(content);
            if (document.RootElement.TryGetProperty("code", out var codeNode)
                && codeNode.ValueKind == JsonValueKind.String)
            {
                code = codeNode.GetString()!;
            }

            if (document.RootElement.TryGetProperty("detail", out var detailNode)
                && detailNode.ValueKind == JsonValueKind.String)
            {
                detail = detailNode.GetString()!;
            }
        }
        catch (JsonException)
        {
            // 非 JSON 错误体保留原文，便于取证
        }

        return new DeviceApiException((int)status, code, detail);
    }
}
