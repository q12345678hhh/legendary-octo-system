using WorkAssistant.Adapters;
using WorkAssistant.Core;
using WorkAssistant.Device;
using WorkAssistant.PrivacyEngine;
using WorkAssistant.Storage;

namespace WorkAssistant.WindowsApp;

/// <summary>
/// 托盘壳：菜单与提示窗完全由 <see cref="TrayStatePresenter"/> 驱动，
/// 壳层不提供隐藏提示、隐藏托盘或静默采集的入口。
/// </summary>
internal sealed class TrayApplicationContext : ApplicationContext
{
    private const string AppVersion = "0.1.0";

    private readonly AgentSettings settings;
    private readonly CaptureSession session = new();
    private readonly IndicatorForm indicator = new();
    private readonly ContextMenuStrip menu = new();
    private readonly NotifyIcon tray = new();
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 60_000 };

    private DeviceIdentity? identity;
    private AgentRuntime? runtime;
    private ForegroundActivityCapture? capture;
    private DeviceApiClient? api;
    private HttpClient? http;
    private int policyVersion;
    private bool disposed;

    public TrayApplicationContext(AgentSettings settings, bool showTray = true)
    {
        this.settings = settings;
        tray.Icon = TrayIconFactory.Create(CaptureState.Stopped);
        tray.Text = "工作助手";
        tray.ContextMenuStrip = menu;
        tray.Visible = showTray;
        tray.DoubleClick += (_, _) =>
        {
            if (Presentation.IndicatorVisible)
            {
                indicator.Show();
            }
        };

        if (settings.IsRegistered)
        {
            InitializeRuntime();
        }

        Refresh();
    }

    public CaptureSession Session => session;

    public TrayPresentation Presentation => TrayStatePresenter.Describe(session.State, policyVersion);

    public bool IndicatorVisible => Presentation.IndicatorVisible;

    /// <summary>当前托盘图标；自检用它核对图标与状态一致。</summary>
    public Icon? TrayIcon => tray.Icon;

    public IReadOnlyList<string> MenuTexts =>
        menu.Items.OfType<ToolStripMenuItem>().Select(item => item.Text ?? string.Empty).ToList();

    /// <summary>依据当前状态重建托盘图标、菜单与提示窗。所有可见性都来自同一个 presenter。</summary>
    public void Refresh()
    {
        var presentation = Presentation;
        tray.Icon = TrayIconFactory.Create(session.State);
        if (presentation.Tooltip.Length > 0)
        {
            tray.Text = presentation.Tooltip.Length <= 63 ? presentation.Tooltip : presentation.Tooltip[..63];
        }

        menu.Items.Clear();
        foreach (var command in presentation.Commands)
        {
            var item = new ToolStripMenuItem(Label(command));
            item.Click += (_, _) => Execute(command);
            menu.Items.Add(item);
        }

        if (presentation.IndicatorVisible)
        {
            indicator.SetText(presentation.IndicatorText);
            if (!indicator.Visible)
            {
                indicator.Show();
            }
        }
        else
        {
            indicator.Hide();
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposed)
        {
            base.Dispose(disposing);
            return;
        }

        disposed = true;
        if (disposing)
        {
            timer.Stop();
            timer.Dispose();
            tray.Visible = false;
            tray.Dispose();
            menu.Dispose();
            indicator.AllowClose = true;
            indicator.Dispose();
            identity?.Dispose();
            http?.Dispose();
        }

        base.Dispose(disposing);
    }

    private static string Label(TrayCommand command) => command switch
    {
        TrayCommand.Start => "开始记录",
        TrayCommand.Pause => "暂停",
        TrayCommand.Resume => "继续",
        TrayCommand.Stop => "结束",
        TrayCommand.EnterPrivateMode => "进入私人模式",
        TrayCommand.ExitPrivateMode => "退出私人模式",
        TrayCommand.Exit => "退出",
        _ => throw new ArgumentOutOfRangeException(nameof(command), command, "未知托盘动作"),
    };

    private void InitializeRuntime()
    {
        identity = DeviceIdentity.LoadOrCreate(settings.Workdir, settings.Tenant!);
        var vault = new DpapiVault(settings.Workdir, identity.DeviceId);
        var queue = new OfflineQueue(vault, TimeSpan.FromHours(72));
        var store = new LocalStateStore(vault);
        http = new HttpClient
        {
            BaseAddress = new Uri(settings.Api!),
            Timeout = TimeSpan.FromSeconds(30),
        };
        api = new DeviceApiClient(http, settings.Tenant!, identity, settings.Subject);
        runtime = new AgentRuntime(session, api, queue, store, identity);
        runtime.Recover();
        capture = new ForegroundActivityCapture(runtime, new WindowsWindowProbe());
        _ = EnrollAsync();
    }

    private async Task EnrollAsync()
    {
        try
        {
            var registration = await api!.RegisterAsync(Environment.MachineName, AppVersion);
            runtime!.ApplyRegistration(registration);
            policyVersion = runtime.AppliedPolicyVersion;
            Refresh();
            timer.Tick += async (_, _) => await OnTickAsync();
            timer.Start();
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or DeviceApiException)
        {
            Notify("设备注册失败，采集与上传保持关闭；请检查网络或联系管理员");
        }
    }

    private async Task OnTickAsync()
    {
        if (runtime is null)
        {
            return;
        }

        // 先采集：网络不可用时队列仍然积累，由后续周期补传。
        TryCapture();

        try
        {
            var heartbeat = await runtime.HeartbeatAsync(AppVersion);
            if (heartbeat.UpdateRequired)
            {
                Notify("存在必须安装的更新，请联系管理员");
            }

            var report = await runtime.FlushAsync();
            if (report.Revoked)
            {
                policyVersion = runtime.AppliedPolicyVersion;
                Refresh();
                Notify("设备已被撤销，采集与上传已停止");
            }
        }
        catch (DeviceApiException e) when (e.IsRevoked)
        {
            runtime.HandleRevoked();
            Refresh();
            Notify("设备已被撤销，采集与上传已停止");
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            // 网络暂时不可用：保留队列，下个周期重试
        }
    }

    private void TryCapture()
    {
        if (capture is null)
        {
            return;
        }

        try
        {
            capture.CaptureOnce();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            // 单个周期采集失败不影响心跳与下一次补传；门禁状态不变。
        }
    }

    private void Execute(TrayCommand command)
    {
        switch (command)
        {
            case TrayCommand.Start:
                if (runtime is null)
                {
                    Notify("设备未注册，无法开始记录");
                    return;
                }

                session.Start();
                break;
            case TrayCommand.Pause:
                session.Pause();
                break;
            case TrayCommand.Resume:
                session.Resume();
                break;
            case TrayCommand.Stop:
                session.Stop();
                _ = FlushAsync();
                break;
            case TrayCommand.EnterPrivateMode:
                session.EnterPrivateMode();
                break;
            case TrayCommand.ExitPrivateMode:
                session.ExitPrivateMode();
                break;
            case TrayCommand.Exit:
                ExitApplication();
                return;
            default:
                throw new ArgumentOutOfRangeException(nameof(command), command, "未知托盘动作");
        }

        Refresh();
    }

    private async Task FlushAsync()
    {
        if (runtime is null)
        {
            return;
        }

        try
        {
            await runtime.FlushAsync();
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            // 结束时网络不可用：条目留在加密队列，下次启动补传
        }
    }

    private void ExitApplication()
    {
        tray.Visible = false;
        Dispose();
        ExitThread();
    }

    private void Notify(string message)
    {
        tray.ShowBalloonTip(5_000, "工作助手", message, ToolTipIcon.Info);
    }
}
