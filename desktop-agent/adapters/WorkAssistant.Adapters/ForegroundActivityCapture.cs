using WorkAssistant.Device;
using WorkAssistant.PrivacyEngine;

namespace WorkAssistant.Adapters;

/// <summary>一次运行时采集的结果。原因用于可见提示与排查，不含窗口正文。</summary>
public sealed record CaptureAttempt(bool Recorded, string? ProcessName, string Reason);

/// <summary>
/// 运行时采集适配：前台快照 → 排除判定 → 采集门禁 → 离线队列。
/// 判定失败（排除、暂停、私人模式、未注册策略）时采集量为 0，且不会读取任何窗口内容。
/// </summary>
public sealed class ForegroundActivityCapture
{
    private readonly AgentRuntime runtime;
    private readonly IWindowProbe probe;
    private readonly TimeSpan segment;

    public ForegroundActivityCapture(AgentRuntime runtime, IWindowProbe probe, TimeSpan? segment = null)
    {
        this.runtime = runtime ?? throw new ArgumentNullException(nameof(runtime));
        this.probe = probe ?? throw new ArgumentNullException(nameof(probe));
        this.segment = segment ?? TimeSpan.FromMinutes(1);
        if (this.segment <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(segment), "采集片段长度必须为正");
        }
    }

    public CaptureAttempt CaptureOnce(DateTimeOffset? at = null)
    {
        var end = at ?? DateTimeOffset.UtcNow;
        var snapshot = probe.ReadForegroundWindow();
        var decision = runtime.Session.EvaluateCapture(snapshot);
        if (decision.Excluded)
        {
            return new CaptureAttempt(false, snapshot.ProcessName, decision.Reason);
        }

        // 摘要只写进程名：窗口标题可能含文档名或聊天对象，不进入离线队列。
        var recorded = runtime.RecordActivity(end - segment, end, snapshot.ProcessName);
        return new CaptureAttempt(recorded, snapshot.ProcessName, recorded ? "已写入离线队列" : "采集门禁拒绝");
    }
}
