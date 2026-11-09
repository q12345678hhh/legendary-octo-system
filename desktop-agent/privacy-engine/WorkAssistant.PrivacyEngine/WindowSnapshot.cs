namespace WorkAssistant.PrivacyEngine;

/// <summary>
/// 前台窗口快照。采集判定只使用这些字段，不接触窗口内容。
/// </summary>
/// <param name="ProcessName">进程名，例如 <c>Code</c>。</param>
/// <param name="WindowTitle">窗口标题。</param>
/// <param name="ExecutablePath">可执行文件路径，可为空。</param>
public sealed record WindowSnapshot(string? ProcessName, string? WindowTitle, string? ExecutablePath);

/// <summary>
/// 禁止采集判定结果。<see cref="Excluded"/> 为 true 时，必须在读取任何内容之前终止采集。
/// </summary>
/// <param name="Excluded">是否排除。</param>
/// <param name="Reason">排除原因，用于可见提示与审计，不含窗口正文。</param>
public sealed record ExclusionDecision(bool Excluded, string Reason);
