namespace WorkAssistant.PrivacyEngine;

/// <summary>采集状态。任何状态都必须对员工可见，不允许隐藏。</summary>
public enum CaptureState
{
    Stopped,
    Capturing,
    Paused,
    PrivateMode,
}

/// <summary>
/// 采集状态机：只有员工显式开始后才可能采集；暂停、私人模式与停止时采集量为 0。
/// 非法迁移直接抛出，避免静默进入采集状态。
/// </summary>
public sealed class CaptureSession
{
    private readonly ForbiddenApplicationPolicy policy;

    public CaptureSession(ForbiddenApplicationPolicy? policy = null)
    {
        this.policy = policy ?? new ForbiddenApplicationPolicy();
    }

    public CaptureState State { get; private set; } = CaptureState.Stopped;

    /// <summary>采集提示是否必须可见。只要不是 Stopped，提示就必须显示。</summary>
    public bool IsIndicatorRequired => State != CaptureState.Stopped;

    public void Start()
    {
        RequireState(CaptureState.Stopped, nameof(Start));
        State = CaptureState.Capturing;
    }

    public void Pause()
    {
        RequireState(CaptureState.Capturing, nameof(Pause));
        State = CaptureState.Paused;
    }

    public void Resume()
    {
        RequireState(CaptureState.Paused, nameof(Resume));
        State = CaptureState.Capturing;
    }

    public void Stop()
    {
        if (State == CaptureState.Stopped)
        {
            throw new InvalidOperationException("采集已经停止");
        }

        State = CaptureState.Stopped;
    }

    public void EnterPrivateMode()
    {
        if (State is CaptureState.Stopped or CaptureState.PrivateMode)
        {
            throw new InvalidOperationException("私人模式只能从采集中或暂停时进入");
        }

        State = CaptureState.PrivateMode;
    }

    public void ExitPrivateMode()
    {
        RequireState(CaptureState.PrivateMode, nameof(ExitPrivateMode));
        State = CaptureState.Paused;
    }

    /// <summary>
    /// 判定当前窗口是否允许采集。必须在前台窗口内容读取之前调用。
    /// </summary>
    public ExclusionDecision EvaluateCapture(WindowSnapshot snapshot)
    {
        if (State != CaptureState.Capturing)
        {
            return new ExclusionDecision(true, $"当前状态为 {State}，不采集");
        }

        return policy.Evaluate(snapshot);
    }

    private void RequireState(CaptureState expected, string operation)
    {
        if (State != expected)
        {
            throw new InvalidOperationException($"状态 {State} 不允许执行 {operation}，期望 {expected}");
        }
    }
}
