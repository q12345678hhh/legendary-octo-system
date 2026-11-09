namespace WorkAssistant.PrivacyEngine;

/// <summary>
/// 强制排除策略：微信、QQ 及平台指定的社交软件在内容读取前即被排除。
/// 策略只能收窄（增加排除），不能由管理员远程放宽；未知进程按排除处理（失败即拒绝）。
/// </summary>
public sealed class ForbiddenApplicationPolicy
{
    /// <summary>默认强制排除的进程名（不区分大小写）。</summary>
    public static readonly IReadOnlySet<string> DefaultForbiddenProcesses = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    {
        "WeChat",
        "Weixin",
        "QQ",
        "TIM",
        "QQNT",
    };

    /// <summary>默认排除的窗口标题关键字（社交软件常见主窗口）。</summary>
    public static readonly IReadOnlyList<string> DefaultForbiddenTitleKeywords = new[]
    {
        "微信",
        "WeChat",
        "QQ",
    };

    private readonly IReadOnlySet<string> forbiddenProcesses;
    private readonly IReadOnlyList<string> forbiddenTitleKeywords;

    public ForbiddenApplicationPolicy()
        : this(DefaultForbiddenProcesses, DefaultForbiddenTitleKeywords)
    {
    }

    public ForbiddenApplicationPolicy(IReadOnlySet<string> forbiddenProcesses, IReadOnlyList<string> forbiddenTitleKeywords)
    {
        this.forbiddenProcesses = forbiddenProcesses;
        this.forbiddenTitleKeywords = forbiddenTitleKeywords;
    }

    public ExclusionDecision Evaluate(WindowSnapshot snapshot)
    {
        ArgumentNullException.ThrowIfNull(snapshot);

        if (string.IsNullOrWhiteSpace(snapshot.ProcessName))
        {
            return new ExclusionDecision(true, "无法识别进程，按失败即拒绝处理");
        }

        if (forbiddenProcesses.Contains(snapshot.ProcessName))
        {
            return new ExclusionDecision(true, $"强制排除应用: {snapshot.ProcessName}");
        }

        foreach (var keyword in forbiddenTitleKeywords)
        {
            if (snapshot.WindowTitle is not null
                && snapshot.WindowTitle.Contains(keyword, StringComparison.OrdinalIgnoreCase))
            {
                return new ExclusionDecision(true, "窗口标题命中强制排除关键字");
            }
        }

        return new ExclusionDecision(false, "允许采集");
    }
}
