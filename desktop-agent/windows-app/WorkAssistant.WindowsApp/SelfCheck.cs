using System.Runtime.InteropServices;
using WorkAssistant.Core;

namespace WorkAssistant.WindowsApp;

/// <summary>
/// 壳层自检：验证托盘菜单与提示窗确实由状态展示层驱动，
/// 并且任何状态都没有隐藏提示或静默运行的入口。
/// </summary>
internal static class SelfCheck
{
    private const int AttachParentProcess = -1;

    public static int Run(string[] args)
    {
        AttachConsole(AttachParentProcess);

        var root = Path.Combine(Path.GetTempPath(), "work-assistant-self-check", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var lines = new List<string>();
        var failures = 0;

        void Expect(string name, bool ok)
        {
            lines.Add((ok ? "PASS windows/" : "FAIL windows/") + name);
            if (!ok)
            {
                failures++;
            }
        }

        using (var context = new TrayApplicationContext(AgentSettings.LocalOnly(root), showTray: false))
        {
            Expect("未记录状态只提供开始与退出",
                context.MenuTexts.SequenceEqual(new[] { "开始记录", "退出" }) && !context.IndicatorVisible);

            context.Session.Start();
            context.Refresh();
            Expect("记录中提示可见且不提供退出",
                context.IndicatorVisible
                && context.MenuTexts.SequenceEqual(new[] { "暂停", "结束", "进入私人模式" }));

            context.Session.Pause();
            context.Refresh();
            Expect("暂停后提示仍可见且文案声明不采集",
                context.IndicatorVisible
                && context.Presentation.IndicatorText.Contains("不采集", StringComparison.Ordinal));

            context.Session.Resume();
            context.Session.EnterPrivateMode();
            context.Refresh();
            Expect("私人模式提示可见且只能退出私人模式或结束",
                context.IndicatorVisible
                && context.MenuTexts.SequenceEqual(new[] { "退出私人模式", "结束" }));

            context.Session.Stop();
            context.Refresh();
            Expect("结束后提示隐藏并重新提供退出",
                !context.IndicatorVisible && context.MenuTexts.SequenceEqual(new[] { "开始记录", "退出" }));
            Expect("托盘菜单不含隐藏或静默入口",
                context.MenuTexts.All(text => !text.Contains("隐藏", StringComparison.Ordinal)
                                              && !text.Contains("静默", StringComparison.Ordinal)));
        }

        try
        {
            Directory.Delete(root, recursive: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Console.WriteLine($"WARN 临时目录未能删除: {root}");
        }

        var reportIndex = Array.IndexOf(args, "--report");
        if (reportIndex >= 0 && reportIndex + 1 < args.Length)
        {
            File.WriteAllLines(args[reportIndex + 1], lines);
        }

        foreach (var line in lines)
        {
            Console.WriteLine(line);
        }

        Console.WriteLine(failures == 0 ? "windows: 全部通过" : $"windows: 失败 {failures} 项");
        return failures == 0 ? 0 : 1;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int processId);
}
