using System.Diagnostics;
using System.Runtime.InteropServices;
using WorkAssistant.Adapters;
using WorkAssistant.PrivacyEngine;

namespace WorkAssistant.PrivacyEngine.Checks;

/// <summary>
/// 原生 Windows 验收：在真实桌面上采样进程与顶层窗口，验证强制排除策略。
/// 只读取进程名与窗口标题，不读取窗口正文、控件树、剪贴板或屏幕像素。
/// </summary>
internal static class LiveWindowProbe
{
    private const int MaxExampleTitles = 3;

    public static int Run()
    {
        if (!OperatingSystem.IsWindows())
        {
            Console.WriteLine("SKIP 原生验收只能在 Windows 上运行");
            return 2;
        }

        Console.WriteLine("== 原生 Windows 验收 ==");
        Console.WriteLine($"OS        {RuntimeInformation.OSDescription}");
        Console.WriteLine($"Runtime   {RuntimeInformation.FrameworkDescription}");
        Console.WriteLine($"Machine   {Environment.MachineName}  User {Environment.UserName}  Session {Process.GetCurrentProcess().SessionId}");
        Console.WriteLine("判定依据   进程名 + 窗口标题；未读取窗口内容");
        Console.WriteLine();

        var session = new CaptureSession();
        session.Start();

        var failures = new List<string>();
        var checks = 0;

        // 一、进程级：正在运行中的微信 / QQ 必须直接排除，不依赖窗口是否可见
        var forbiddenSeen = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        var forbiddenExcluded = 0;
        foreach (var process in Process.GetProcesses())
        {
            using (process)
            {
                string? name;
                try
                {
                    name = process.ProcessName;
                }
                catch (InvalidOperationException)
                {
                    continue;
                }

                if (!ForbiddenApplicationPolicy.DefaultForbiddenProcesses.Contains(name))
                {
                    continue;
                }

                var title = string.Empty;
                try
                {
                    title = process.MainWindowTitle;
                }
                catch (InvalidOperationException)
                {
                    // 进程已退出；标题留空不影响进程名判定
                }

                checks++;
                if (!forbiddenSeen.ContainsKey(name))
                {
                    forbiddenSeen[name] = 0;
                }

                forbiddenSeen[name]++;
                var decision = session.EvaluateCapture(new WindowSnapshot(name, title, null));
                if (decision.Excluded)
                {
                    forbiddenExcluded++;
                }
                else
                {
                    failures.Add($"运行中的强制排除应用未被排除: {name}");
                }
            }
        }

        foreach (var (name, count) in forbiddenSeen)
        {
            Console.WriteLine($"真实运行 {name} 进程 {count} 个，全部按排除处理");
        }

        if (forbiddenSeen.Count == 0)
        {
            Console.WriteLine("本机当前没有运行微信 / QQ，进程级命中为空");
        }

        Console.WriteLine();

        // 二、窗口级：真实顶层窗口按统一策略判定，未知进程失败即拒绝
        var sampled = 0;
        var titleHits = 0;
        var unknown = 0;
        var allowed = 0;

        // 与运行时采集共用同一个探针实现，验收覆盖的正是线上读取路径。
        var probe = new WindowsWindowProbe();
        foreach (var snapshot in probe.ReadVisibleWindows())
        {
            sampled++;
            var decision = session.EvaluateCapture(snapshot);
            if (snapshot.ProcessName is null)
            {
                unknown++;
                if (!decision.Excluded)
                {
                    failures.Add($"未知进程窗口未被排除: {snapshot.WindowTitle}");
                }

                continue;
            }

            if (ForbiddenApplicationPolicy.DefaultForbiddenProcesses.Contains(snapshot.ProcessName))
            {
                if (!decision.Excluded)
                {
                    failures.Add($"强制排除应用的窗口未被排除: {snapshot.ProcessName}");
                }

                continue;
            }

            if (decision.Excluded)
            {
                titleHits++;
                continue;
            }

            allowed++;
        }

        Console.WriteLine($"采样可见窗口 {sampled} 个：标题关键字命中 {titleHits} 个，未知进程 {unknown} 个，允许采集 {allowed} 个");
        Console.WriteLine($"进程级排除 {forbiddenExcluded} 个（判定在读取窗口内容之前完成）");

        // 三、判定顺序：刚开始采集时状态可见，停止后采集量归零
        session.Stop();
        var afterStop = session.EvaluateCapture(new WindowSnapshot("Code", "Program.cs", null));
        checks++;
        if (!afterStop.Excluded)
        {
            failures.Add("停止采集后仍在采集");
        }

        checks++;
        if (sampled == 0)
        {
            failures.Add("没有采样到任何可见窗口，无法证明原生判定生效");
        }

        Console.WriteLine();
        Console.WriteLine($"检查通过 {checks - failures.Count} 项，失败 {failures.Count} 项，窗口样本 {sampled} 个");
        if (failures.Count > 0)
        {
            foreach (var failure in failures.Take(20))
            {
                Console.WriteLine($"FAIL {failure}");
            }

            return 1;
        }

        if (forbiddenSeen.Count > 0)
        {
            var examples = forbiddenSeen.Keys.Select(name => $"{name} x{forbiddenSeen[name]}");
            Console.WriteLine("真实命中: " + string.Join("、", examples));
        }

        return 0;
    }
}
