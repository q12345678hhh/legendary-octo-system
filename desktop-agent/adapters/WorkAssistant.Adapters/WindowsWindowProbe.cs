using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using WorkAssistant.PrivacyEngine;

namespace WorkAssistant.Adapters;

/// <summary>
/// 真实 Windows 窗口探针。只读取进程名与窗口标题，不读取窗口正文、控件树、剪贴板或屏幕像素。
/// </summary>
public sealed class WindowsWindowProbe : IWindowProbe
{
    public WindowSnapshot ReadForegroundWindow()
    {
        EnsureWindows();
        var handle = GetForegroundWindow();
        return handle == IntPtr.Zero ? Unknown : Read(handle);
    }

    public IReadOnlyList<WindowSnapshot> ReadVisibleWindows()
    {
        EnsureWindows();
        var snapshots = new List<WindowSnapshot>();
        EnumWindows((handle, _) =>
        {
            if (!IsWindowVisible(handle) || GetWindowTextLength(handle) <= 0)
            {
                return true;
            }

            snapshots.Add(Read(handle));
            return true;
        }, IntPtr.Zero);
        return snapshots;
    }

    private static WindowSnapshot Read(IntPtr handle)
    {
        var length = GetWindowTextLength(handle);
        var buffer = new StringBuilder(length + 1);
        var title = GetWindowText(handle, buffer, buffer.Capacity) > 0 ? buffer.ToString() : string.Empty;
        GetWindowThreadProcessId(handle, out var processId);
        return new WindowSnapshot(ProcessName(processId), title, null);
    }

    private static string? ProcessName(uint processId)
    {
        try
        {
            using var process = Process.GetProcessById((int)processId);
            return process.ProcessName;
        }
        catch (ArgumentException)
        {
            // 进程在采样间隙退出：按未知进程处理，调用方会失败即拒绝
            return null;
        }
        catch (InvalidOperationException)
        {
            return null;
        }
    }

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new InvalidOperationException("窗口探针只能在 Windows 上运行");
        }
    }

    private static WindowSnapshot Unknown => new(null, null, null);

    private delegate bool EnumWindowsProc(IntPtr handle, IntPtr parameter);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr handle);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowTextLength(IntPtr handle);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr handle, StringBuilder text, int count);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);
}
