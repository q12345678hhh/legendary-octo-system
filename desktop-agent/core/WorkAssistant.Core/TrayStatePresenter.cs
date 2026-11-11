using WorkAssistant.PrivacyEngine;

namespace WorkAssistant.Core;

/// <summary>托盘菜单动作。刻意没有「隐藏提示」「隐藏托盘」「静默运行」。</summary>
public enum TrayCommand
{
    Start,
    Pause,
    Resume,
    Stop,
    EnterPrivateMode,
    ExitPrivateMode,
    Exit,
}

/// <summary>某个采集状态下托盘与提示窗的完整展示快照。</summary>
public sealed record TrayPresentation(
    string Title,
    string Tooltip,
    bool IndicatorVisible,
    string IndicatorText,
    IReadOnlyList<TrayCommand> Commands,
    bool CanExit);

/// <summary>
/// 唯一的状态展示来源：壳层只读这里的结果，不能自行决定隐藏提示。
/// </summary>
public static class TrayStatePresenter
{
    public static TrayPresentation Describe(CaptureState state, int policyVersion)
    {
        var version = $"策略 v{policyVersion}";
        return state switch
        {
            CaptureState.Stopped => new TrayPresentation(
                "工作助手：未记录",
                $"未记录（{version}）",
                IndicatorVisible: false,
                IndicatorText: string.Empty,
                Commands: new[] { TrayCommand.Start, TrayCommand.Exit },
                CanExit: true),

            CaptureState.Capturing => new TrayPresentation(
                "工作助手：记录中",
                $"记录中（{version}）",
                IndicatorVisible: true,
                IndicatorText: $"工作助手：记录中（{version}）",
                Commands: new[] { TrayCommand.Pause, TrayCommand.Stop, TrayCommand.EnterPrivateMode },
                CanExit: false),

            CaptureState.Paused => new TrayPresentation(
                "工作助手：已暂停",
                $"已暂停，不采集（{version}）",
                IndicatorVisible: true,
                IndicatorText: $"工作助手：已暂停，不采集（{version}）",
                Commands: new[] { TrayCommand.Resume, TrayCommand.Stop, TrayCommand.EnterPrivateMode },
                CanExit: false),

            CaptureState.PrivateMode => new TrayPresentation(
                "工作助手：私人模式",
                $"私人模式，不采集（{version}）",
                IndicatorVisible: true,
                IndicatorText: $"工作助手：私人模式，不采集（{version}）",
                Commands: new[] { TrayCommand.ExitPrivateMode, TrayCommand.Stop },
                CanExit: false),

            _ => throw new ArgumentOutOfRangeException(nameof(state), state, "未知采集状态"),
        };
    }
}
