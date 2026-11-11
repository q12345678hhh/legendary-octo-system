using System.Text.Json;

namespace WorkAssistant.Storage;

/// <summary>本机持久状态：只保存策略版本、设备状态与固定公钥，不保存会话状态。</summary>
public sealed record AgentState(
    int AppliedPolicyVersion,
    string DeviceStatus,
    string? DeviceId,
    string? PolicyKeyId,
    string? UpdateKeyId,
    string? PolicyPublicKeySpki,
    string? UpdatePublicKeySpki,
    DateTimeOffset UpdatedAt)
{
    public static AgentState Empty { get; } = new(
        0, "unregistered", null, null, null, null, null, DateTimeOffset.MinValue);
}

/// <summary>
/// 本地状态存储。会话状态故意不落盘：进程崩溃或重启后一律从未记录开始。
/// 读取失败按「未注册」处理（失败即拒绝），不会尝试恢复可疑数据。
/// </summary>
public sealed class LocalStateStore
{
    private const string StatePath = "state.json";

    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly DpapiVault vault;

    public LocalStateStore(DpapiVault vault)
    {
        this.vault = vault;
    }

    public AgentState Load()
    {
        if (!vault.Exists(StatePath))
        {
            return AgentState.Empty;
        }

        try
        {
            return JsonSerializer.Deserialize<AgentState>(vault.Read(StatePath), Json) ?? AgentState.Empty;
        }
        catch (Exception e) when (e is System.Security.Cryptography.CryptographicException
                                        or JsonException or InvalidDataException)
        {
            return AgentState.Empty;
        }
    }

    public void Save(AgentState state)
    {
        ArgumentNullException.ThrowIfNull(state);
        vault.Write(StatePath, JsonSerializer.SerializeToUtf8Bytes(
            state with { UpdatedAt = DateTimeOffset.UtcNow }, Json));
    }
}
