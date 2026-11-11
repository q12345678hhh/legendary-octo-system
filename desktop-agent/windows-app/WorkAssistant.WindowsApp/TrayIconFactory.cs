using System.Drawing;
using System.Runtime.InteropServices;
using WorkAssistant.PrivacyEngine;

namespace WorkAssistant.WindowsApp;

/// <summary>
/// 托盘图标按采集状态绘制：四种状态颜色互不相同，员工从托盘即可分辨当前是否在采集。
/// 图标按状态缓存，句柄在生成后立即释放，避免每次刷新泄漏。
/// </summary>
internal static class TrayIconFactory
{
    private static readonly Dictionary<CaptureState, Icon> Cache = new();
    private static readonly object Sync = new();

    public static Color ColorOf(CaptureState state) => state switch
    {
        CaptureState.Stopped => Color.FromArgb(255, 107, 114, 128),
        CaptureState.Capturing => Color.FromArgb(255, 22, 163, 74),
        CaptureState.Paused => Color.FromArgb(255, 217, 119, 6),
        CaptureState.PrivateMode => Color.FromArgb(255, 124, 58, 237),
        _ => throw new ArgumentOutOfRangeException(nameof(state), state, "未知采集状态"),
    };

    public static Icon Create(CaptureState state)
    {
        lock (Sync)
        {
            if (!Cache.TryGetValue(state, out var icon))
            {
                icon = Render(state);
                Cache[state] = icon;
            }

            return icon;
        }
    }

    private static Icon Render(CaptureState state)
    {
        using var bitmap = new Bitmap(16, 16);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.Clear(Color.Transparent);
            using var brush = new SolidBrush(ColorOf(state));
            graphics.FillEllipse(brush, 1, 1, 14, 14);
            using var pen = new Pen(Color.FromArgb(96, Color.Black), 1f);
            graphics.DrawEllipse(pen, 1, 1, 13, 13);
        }

        var handle = bitmap.GetHicon();
        try
        {
            return (Icon)Icon.FromHandle(handle).Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr handle);
}
