namespace WorkAssistant.PrivacyEngine;

/// <summary>
/// 前台窗口探针：只读取进程名与窗口标题等元数据，不读取窗口正文、控件树、剪贴板或屏幕像素。
/// 运行时采集与验收工具共用同一接口，保证「排除判定先于内容读取」只有一条路径。
/// </summary>
public interface IWindowProbe
{
    /// <summary>读取当前前台窗口；无法识别时返回字段为空的快照（调用方按失败即拒绝处理）。</summary>
    WindowSnapshot ReadForegroundWindow();

    /// <summary>枚举当前可见的顶层窗口快照，用于验收与取证，不用于采集循环。</summary>
    IReadOnlyList<WindowSnapshot> ReadVisibleWindows();
}
