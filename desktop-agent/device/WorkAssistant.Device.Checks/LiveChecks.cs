using WorkAssistant.Core;
using WorkAssistant.PrivacyEngine;
using WorkAssistant.Storage;

namespace WorkAssistant.Device.Checks;

/// <summary>
/// 真实链路取证：对运行中的 API 完成注册 → 上传 → 暂停零采集 → 崩溃恢复 → 撤销后拒绝上传。
/// 每天阶段独立进程执行，模拟真实安装、崩溃与运维撤销，不共享内存状态。
/// </summary>
internal static class LiveChecks
{
    private const string AppVersion = "0.1.0";

    public static async Task<int> RunAsync(LiveOptions options)
    {
        var checks = new Checks($"live/{options.Phase}");
        var directory = Path.GetFullPath(options.Workdir);
        Directory.CreateDirectory(directory);

        using var identity = DeviceIdentity.LoadOrCreate(directory, options.Tenant);
        Console.WriteLine($"DEVICE {identity.DeviceId}");
        var vault = new DpapiVault(directory, identity.DeviceId);
        var store = new LocalStateStore(vault);
        var queue = new OfflineQueue(vault, TimeSpan.FromHours(72));
        using var http = new HttpClient
        {
            BaseAddress = new Uri(options.Api),
            Timeout = TimeSpan.FromSeconds(30),
        };
        var api = new DeviceApiClient(http, options.Tenant, identity, options.Subject);
        var session = new CaptureSession();
        var runtime = new AgentRuntime(session, api, queue, store, identity);

        switch (options.Phase)
        {
            case "enroll":
                await EnrollAsync(checks, runtime, api, session, queue);
                break;
            case "crash":
                await CrashAsync(checks, api, store, queue, session);
                break;
            case "recover":
                await RecoverAsync(checks, runtime, queue);
                break;
            case "revoke":
                await RevokeAsync(checks, api, store, queue, session, runtime);
                break;
            default:
                Console.WriteLine($"INVALID 未知阶段: {options.Phase}");
                return 2;
        }

        return checks.Report();
    }

    private static async Task EnrollAsync(Checks checks, AgentRuntime runtime, DeviceApiClient api,
        CaptureSession session, OfflineQueue queue)
    {
        var registration = await api.RegisterAsync(Environment.MachineName, AppVersion);
        runtime.ApplyRegistration(registration);

        checks.Check("注册返回可验签策略",
            () => runtime.Policy is { PolicyVersion: >= 1 } && runtime.Policy.Processes.Contains("WeChat"));
        checks.Check("注册后设备状态为 active", () => runtime.State.DeviceStatus == "active");

        session.Start();
        checks.Check("开始后产生两条离线片段",
            () => runtime.RecordActivity(TimeSpan.FromMinutes(1), "live-1")
                  && runtime.RecordActivity(TimeSpan.FromMinutes(2), "live-2")
                  && queue.Count == 2);

        var flush = await runtime.FlushAsync();
        checks.Check("补传成功后清空离线队列", () => flush is { Uploaded: 2, Remaining: 0, Revoked: false });

        session.Pause();
        checks.Check("暂停后采集量为 0",
            () => !runtime.RecordActivity(TimeSpan.FromMinutes(1), "paused") && queue.Count == 0);
        checks.Check("暂停后采集提示仍可见",
            () => TrayStatePresenter.Describe(session.State, runtime.AppliedPolicyVersion).IndicatorVisible);

        var heartbeat = await runtime.HeartbeatAsync(AppVersion);
        checks.Check("心跳上报版本且当前版本无需更新",
            () => heartbeat is { Status: "active", UpdateRequired: false } && heartbeat.MinSupportedVersion.Length > 0);
        var outdated = await runtime.HeartbeatAsync("0.0.1");
        checks.Check("低于最低支持版本时要求更新", () => outdated.UpdateRequired);
    }

    private static async Task CrashAsync(Checks checks, DeviceApiClient api, LocalStateStore store,
        OfflineQueue queue, CaptureSession session)
    {
        var policy = await HydratePolicyAsync(api, store);
        session.Start();
        var now = DateTimeOffset.UtcNow;
        var created = CaptureGate.TryDraft(session, policy, now.AddMinutes(-1), now, "crash-pending", out var draft);
        checks.Check("崩溃前生成待上传片段", () => created && draft is not null);
        if (draft is null)
        {
            return;
        }

        queue.Enqueue(draft);
        var result = await api.UploadAsync(draft);
        checks.Check("上传成功但本地条目未删除（模拟崩溃时机）", () => result.Accepted && queue.Count == 1);
        Console.WriteLine($"CRASH-SIMULATED {draft.ClientSegmentId:D}");
    }

    private static async Task RecoverAsync(Checks checks, AgentRuntime runtime, OfflineQueue queue)
    {
        var recovery = runtime.Recover();
        checks.Check("崩溃恢复保留未确认条目", () => recovery.KeptEntries == 1 && queue.Count == 1);
        checks.Check("恢复后会话从未记录开始", () => runtime.Session.State == CaptureState.Stopped);

        var flush = await runtime.FlushAsync();
        checks.Check("重传被服务端去重且队列清空",
            () => flush is { Duplicates: 1, Uploaded: 0, Remaining: 0 });
    }

    private static async Task RevokeAsync(Checks checks, DeviceApiClient api, LocalStateStore store,
        OfflineQueue queue, CaptureSession session, AgentRuntime runtime)
    {
        var policy = await HydratePolicyAsync(api, store);
        session.Start();
        var now = DateTimeOffset.UtcNow;
        var created = CaptureGate.TryDraft(session, policy, now.AddMinutes(-1), now, "revoke-pending", out var draft);
        checks.Check("撤销前生成待上传片段", () => created && draft is not null);
        if (draft is not null)
        {
            queue.Enqueue(draft);
        }

        await api.RevokeAsync();
        var flush = await runtime.FlushAsync();
        checks.Check("撤销后上传被服务端拒绝", () => flush.Revoked);
        checks.Check("撤销后清空离线队列", () => queue.Count == 0);
        checks.Check("撤销后采集立即停止", () => session.State == CaptureState.Stopped);

        var blocked = false;
        if (draft is not null)
        {
            try
            {
                await api.UploadAsync(draft);
            }
            catch (DeviceApiException error)
            {
                blocked = error.IsRevoked;
            }
        }

        checks.Check("撤销后直接上传返回 403 DEVICE_REVOKED", () => blocked);
    }

    private static async Task<PolicySnapshot> HydratePolicyAsync(DeviceApiClient api, LocalStateStore store)
    {
        var state = store.Load();
        if (state.PolicyPublicKeySpki is null || state.PolicyKeyId is null)
        {
            throw new InvalidOperationException("设备尚未注册，请先执行 --phase enroll");
        }

        var json = await api.GetPolicyJsonAsync();
        return PolicyVerifier.Verify(json, state.PolicyPublicKeySpki, state.PolicyKeyId, 0);
    }
}

/// <summary>命令行参数。缺少必填项时返回错误而不是抛异常，便于脚本给出可读提示。</summary>
internal sealed record LiveOptions(string Api, string Tenant, string? Subject, string Workdir, string Phase)
{
    private static readonly string[] Phases = { "enroll", "crash", "recover", "revoke" };

    public static bool TryParse(string[] args, out LiveOptions? options, out string? error)
    {
        options = null;
        error = null;

        var api = Value(args, "--api");
        var tenant = Value(args, "--tenant");
        var phase = Value(args, "--phase") ?? "enroll";
        var workdir = Value(args, "--workdir")
                      ?? Path.Combine(Path.GetTempPath(), "work-assistant-live");

        if (string.IsNullOrWhiteSpace(api)
            || !Uri.TryCreate(api, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            error = "--api 必须是 http/https 绝对地址";
            return false;
        }

        if (string.IsNullOrWhiteSpace(tenant))
        {
            error = "--tenant 必填";
            return false;
        }

        if (!Phases.Contains(phase, StringComparer.Ordinal))
        {
            error = $"--phase 必须是 {string.Join('|', Phases)}";
            return false;
        }

        options = new LiveOptions(api.TrimEnd('/'), tenant, Value(args, "--subject"), workdir, phase);
        return true;
    }

    private static string? Value(string[] args, string name)
    {
        var index = Array.IndexOf(args, name);
        return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
    }
}
