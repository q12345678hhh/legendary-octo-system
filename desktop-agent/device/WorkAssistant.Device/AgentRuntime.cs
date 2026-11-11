using WorkAssistant.Core;
using WorkAssistant.PrivacyEngine;
using WorkAssistant.Storage;

namespace WorkAssistant.Device;

public sealed record FlushReport(int Uploaded, int Duplicates, int Remaining, bool Revoked);

/// <summary>
/// 设备运行时：把采集状态机、策略、离线队列与设备 API 串起来。
///
/// <p>壳层只通过这里迁移状态；所有采集与上传判定都在无 UI 的代码里，便于自动取证。
/// </summary>
public sealed class AgentRuntime
{
    private readonly DeviceApiClient api;
    private readonly OfflineQueue queue;
    private readonly LocalStateStore store;
    private readonly DeviceIdentity identity;

    public AgentRuntime(CaptureSession session, DeviceApiClient api, OfflineQueue queue,
        LocalStateStore store, DeviceIdentity identity)
    {
        Session = session;
        this.api = api;
        this.queue = queue;
        this.store = store;
        this.identity = identity;
        State = store.Load();
    }

    public CaptureSession Session { get; }

    public AgentState State { get; private set; }

    public PolicySnapshot? Policy { get; private set; }

    public int AppliedPolicyVersion => State.AppliedPolicyVersion;

    /// <summary>注册或重新注册后固定验签公钥，并应用首份策略。</summary>
    public void ApplyRegistration(RegistrationInfo info)
    {
        var policy = PolicyVerifier.Verify(info.PolicyJson, info.PolicyPublicKeySpki,
            info.PolicyKeyId, lastAppliedVersion: 0);
        Policy = policy;
        State = new AgentState(policy.PolicyVersion, info.Status, identity.DeviceId,
            info.PolicyKeyId, info.UpdateKeyId, info.PolicyPublicKeySpki, info.UpdatePublicKeySpki,
            DateTimeOffset.UtcNow);
        store.Save(State);
    }

    /// <summary>拉取新策略；版本必须前进，未知字段或验签失败会抛异常并保留旧策略。</summary>
    public async Task RefreshPolicyAsync(CancellationToken cancellationToken = default)
    {
        if (State.PolicyPublicKeySpki is null || State.PolicyKeyId is null)
        {
            throw new InvalidOperationException("设备尚未注册，无法拉取策略");
        }

        var json = await api.GetPolicyJsonAsync(cancellationToken);
        var snapshot = PolicyVerifier.Verify(json, State.PolicyPublicKeySpki, State.PolicyKeyId,
            State.AppliedPolicyVersion);
        Policy = snapshot;
        State = State with { AppliedPolicyVersion = snapshot.PolicyVersion };
        store.Save(State);
    }

    /// <summary>只有采集中且策略允许上传时才产生片段并进入离线队列。</summary>
    public bool RecordActivity(DateTimeOffset capturedFrom, DateTimeOffset capturedTo, string? summary)
    {
        if (Policy is null)
        {
            return false;
        }

        if (!CaptureGate.TryDraft(Session, Policy, capturedFrom, capturedTo, summary, out var draft)
            || draft is null)
        {
            return false;
        }

        queue.Enqueue(draft);
        return true;
    }

    public bool RecordActivity(TimeSpan duration, string? summary)
    {
        var now = DateTimeOffset.UtcNow;
        return RecordActivity(now - duration, now, summary);
    }

    /// <summary>补传离线队列；成功或服务端判定重复的条目才删除。</summary>
    public async Task<FlushReport> FlushAsync(CancellationToken cancellationToken = default)
    {
        var uploaded = 0;
        var duplicates = 0;
        foreach (var entry in queue.ReadAll())
        {
            try
            {
                var result = await api.UploadAsync(entry.Draft, cancellationToken);
                queue.Remove(entry.Draft.ClientSegmentId);
                if (result.Duplicate)
                {
                    duplicates++;
                }
                else
                {
                    uploaded++;
                }
            }
            catch (DeviceApiException e) when (e.IsRevoked)
            {
                HandleRevoked();
                return new FlushReport(uploaded, duplicates, queue.Count, Revoked: true);
            }
            catch (DeviceApiException)
            {
                // 策略刚好被追加导致 409 等：保留条目，等客户端刷新策略后重试
                break;
            }
            catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
            {
                break;
            }
        }

        return new FlushReport(uploaded, duplicates, queue.Count, Revoked: false);
    }

    public async Task<HeartbeatResult> HeartbeatAsync(string appVersion,
        CancellationToken cancellationToken = default)
    {
        var result = await api.HeartbeatAsync(appVersion, State.AppliedPolicyVersion, cancellationToken);
        if (string.Equals(result.Status, "revoked", StringComparison.Ordinal))
        {
            HandleRevoked();
        }

        return result;
    }

    /// <summary>设备被撤销：停采集、清空离线队列、标记状态；不能继续上传。</summary>
    public void HandleRevoked()
    {
        if (Session.State != CaptureState.Stopped)
        {
            Session.Stop();
        }

        queue.Clear();
        State = State with { DeviceStatus = "revoked" };
        store.Save(State);
    }

    /// <summary>启动时调用：恢复队列、清理临时文件，并把会话状态重置为未记录。</summary>
    public QueueRecoveryReport Recover()
    {
        return queue.Recover();
    }
}
