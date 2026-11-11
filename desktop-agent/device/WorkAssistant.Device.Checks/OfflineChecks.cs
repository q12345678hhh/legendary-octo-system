using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using WorkAssistant.Adapters;
using WorkAssistant.Core;
using WorkAssistant.PrivacyEngine;
using WorkAssistant.Storage;

namespace WorkAssistant.Device.Checks;

/// <summary>
/// 离线自检：不依赖网络与服务器，验证采集门禁、托盘提示、DPAPI、离线队列、
/// 策略验签与更新回滚。CI 与取证脚本都直接消费 PASS/FAIL 行与退出码。
/// </summary>
internal static class OfflineChecks
{
    public static int Run()
    {
        var checks = new Checks("offline");
        var root = Path.Combine(Path.GetTempPath(), "work-assistant-checks", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            GateChecks(checks);
            TrayChecks(checks);
            VaultChecks(checks, root);
            QueueChecks(checks, root);
            PolicyChecks(checks);
            IdentityChecks(checks, root);
            UpdateChecks(checks, root);
            CaptureAdapterChecks(checks, root);
            InstallerChecks(checks, root);
        }
        finally
        {
            TryDelete(root);
        }

        return checks.Report();
    }

    private static void GateChecks(Checks checks)
    {
        using var signer = new TestPolicySigner();
        var now = DateTimeOffset.UtcNow;
        var snapshot = PolicyVerifier.Verify(
            signer.Json(3, new[] { "Calculator" }, Array.Empty<string>(), true),
            signer.PublicKeySpki, TestPolicySigner.KeyId, 0);

        checks.Check("策略进程为服务端追加项与本地硬编码下限的并集",
            () => snapshot.Processes.Contains("Calculator") && snapshot.Processes.Contains("WeChat"));
        checks.Check("策略关键字包含本地硬编码下限",
            () => snapshot.TitleKeywords.Contains("微信", StringComparer.OrdinalIgnoreCase));

        var session = new CaptureSession();
        checks.Check("未开始时采集量为 0", () => !Draft(session, snapshot, now));
        session.Start();
        checks.Check("采集中允许产生片段", () => Draft(session, snapshot, now, out var draft)
            && draft is not null && draft.PolicyVersion == 3);
        session.Pause();
        checks.Check("暂停时采集量为 0", () => !Draft(session, snapshot, now));
        session.Resume();
        session.EnterPrivateMode();
        checks.Check("私人模式下采集量为 0", () => !Draft(session, snapshot, now));
        session.Stop();
        checks.Check("停止后采集量为 0", () => !Draft(session, snapshot, now));

        session.Start();
        var uploadDisabled = PolicyVerifier.Verify(
            signer.Json(4, Array.Empty<string>(), Array.Empty<string>(), false),
            signer.PublicKeySpki, TestPolicySigner.KeyId, 0);
        checks.Check("策略关闭上传时采集量为 0", () => !Draft(session, uploadDisabled, now));
        checks.Check("采集中仍对聊天应用强制排除",
            () => session.EvaluateCapture(new WindowSnapshot("WeChat", "微信", null)).Excluded);
    }

    private static void TrayChecks(Checks checks)
    {
        foreach (var state in Enum.GetValues<CaptureState>())
        {
            var presentation = TrayStatePresenter.Describe(state, 7);
            var mustShow = state != CaptureState.Stopped;
            checks.Check($"状态 {state} 的提示可见性", () => presentation.IndicatorVisible == mustShow);
            checks.Check($"状态 {state} 的提示文案", () => !mustShow || presentation.IndicatorText.Length > 0);
            checks.Check($"状态 {state} 的退出可用性",
                () => presentation.Commands.Contains(TrayCommand.Exit) == (state == CaptureState.Stopped));
        }

        checks.Check("托盘动作不含隐藏或静默入口", () => Enum.GetNames<TrayCommand>()
            .All(name => !name.Contains("Hide", StringComparison.OrdinalIgnoreCase)
                         && !name.Contains("Silent", StringComparison.OrdinalIgnoreCase)
                         && !name.Contains("Invisible", StringComparison.OrdinalIgnoreCase)));
        checks.Check("采集中必须提供私人模式",
            () => TrayStatePresenter.Describe(CaptureState.Capturing, 7).Commands
                .Contains(TrayCommand.EnterPrivateMode));
        checks.Check("暂停时提示文案声明不采集",
            () => TrayStatePresenter.Describe(CaptureState.Paused, 7).IndicatorText.Contains("不采集", StringComparison.Ordinal));
    }

    private static void VaultChecks(Checks checks, string root)
    {
        var vault = new DpapiVault(Path.Combine(root, "vault"), "device-a");
        var secret = Encoding.UTF8.GetBytes("WORK-ASSISTANT-PLAINTEXT-PROBE");
        vault.Write("probe.bin", secret);
        var stored = File.ReadAllBytes(Path.Combine(vault.RootDirectory, "probe.bin"));
        checks.Check("DPAPI 加密后文件不含明文",
            () => !Encoding.UTF8.GetString(stored).Contains("WORK-ASSISTANT-PLAINTEXT-PROBE", StringComparison.Ordinal));
        checks.Check("DPAPI 可解密回原文", () => vault.Read("probe.bin").SequenceEqual(secret));
        checks.Check("原子写不留下临时文件",
            () => Directory.GetFiles(vault.RootDirectory, "*.tmp", SearchOption.AllDirectories).Length == 0);

        var foreign = new DpapiVault(vault.RootDirectory, "device-b");
        checks.Throws<CryptographicException>("换设备熵无法解密", () => foreign.Read("probe.bin"));
        checks.Throws<ArgumentException>("相对路径越界被拒绝", () => vault.Write("../escape.bin", secret));

        var store = new LocalStateStore(new DpapiVault(Path.Combine(root, "state"), "device-a"));
        checks.Check("未注册时状态为未注册且策略版本为 0",
            () => store.Load() is { DeviceStatus: "unregistered", AppliedPolicyVersion: 0 });
        store.Save(new AgentState(5, "active", "dev-1", "key", "update-key", "spki", "uspki", DateTimeOffset.UtcNow));
        checks.Check("本机状态可持久化",
            () => store.Load() is { AppliedPolicyVersion: 5, DeviceStatus: "active" });
    }

    private static void QueueChecks(Checks checks, string root)
    {
        var now = DateTimeOffset.UtcNow;
        var clock = now;
        var queue = new OfflineQueue(
            new DpapiVault(Path.Combine(root, "queue"), "device-a"), TimeSpan.FromHours(72), () => clock);
        var draft = new SegmentDraft(Guid.NewGuid(), now, now.AddMinutes(1), "window", "q1", 3);
        queue.Enqueue(draft);
        checks.Check("离线条目可写入并读回",
            () => queue.Count == 1 && queue.ReadAll()[0].Draft.ClientSegmentId == draft.ClientSegmentId);

        clock = now + TimeSpan.FromHours(73);
        checks.Check("离线条目超过 72 小时后删除", () => queue.PurgeExpired() == 1 && queue.Count == 0);

        var recoveryVault = new DpapiVault(Path.Combine(root, "recover"), "device-a");
        var recoveryQueue = new OfflineQueue(recoveryVault, TimeSpan.FromHours(72));
        recoveryQueue.Enqueue(new SegmentDraft(Guid.NewGuid(), now, now.AddMinutes(1), "window", "keep", 3));
        var queueDirectory = Path.Combine(recoveryVault.RootDirectory, "queue");
        File.WriteAllText(Path.Combine(queueDirectory, "half-written.tmp"), "temp");
        File.WriteAllBytes(Path.Combine(queueDirectory, $"{Guid.NewGuid():D}.seg"), new byte[] { 1, 2, 3, 4 });

        var report = recoveryQueue.Recover();
        checks.Check("崩溃恢复删除未完成写入的临时文件", () => report.DeletedTemporaryFiles == 1);
        checks.Check("崩溃恢复隔离损坏条目并保留有效条目",
            () => report is { QuarantinedEntries: 1, KeptEntries: 1 } && recoveryQueue.Count == 1);
        checks.Check("隔离区保留损坏条目供排查",
            () => Directory.GetFiles(Path.Combine(recoveryVault.RootDirectory, "quarantine")).Length == 1);
    }

    private static void PolicyChecks(Checks checks)
    {
        using var signer = new TestPolicySigner();
        var valid = signer.Json(5, new[] { "Calculator" }, Array.Empty<string>(), true);
        checks.Check("合法策略验签通过",
            () => PolicyVerifier.Verify(valid, signer.PublicKeySpki, TestPolicySigner.KeyId, 4).PolicyVersion == 5);
        checks.Throws<InvalidOperationException>("策略版本不前进时拒绝回滚",
            () => PolicyVerifier.Verify(valid, signer.PublicKeySpki, TestPolicySigner.KeyId, 5));
        checks.Throws<InvalidOperationException>("策略 key id 不匹配时拒绝",
            () => PolicyVerifier.Verify(valid, signer.PublicKeySpki, "other-key", 0));
        checks.Throws<InvalidOperationException>("策略签名被篡改时拒绝",
            () => PolicyVerifier.Verify(
                signer.Json(6, new[] { "Calculator" }, Array.Empty<string>(), true, signatureOverride: "AAAA"),
                signer.PublicKeySpki, TestPolicySigner.KeyId, 0));
        checks.Throws<JsonException>("策略含未知字段时拒收",
            () => PolicyVerifier.Verify(
                signer.Json(6, Array.Empty<string>(), Array.Empty<string>(), true, extraField: "\"captureEnabled\":true"),
                signer.PublicKeySpki, TestPolicySigner.KeyId, 0));
    }

    private static void IdentityChecks(Checks checks, string root)
    {
        var directory = Path.Combine(root, "identity");
        using var first = DeviceIdentity.LoadOrCreate(directory, "tenant-a");
        using var second = DeviceIdentity.LoadOrCreate(directory, "tenant-a");
        using var other = DeviceIdentity.LoadOrCreate(directory, "tenant-b");

        checks.Check("同机同租户设备身份稳定",
            () => first.DeviceId == second.DeviceId && first.PublicKeySpki == second.PublicKeySpki);
        checks.Check("不同租户设备身份隔离", () => first.DeviceId != other.DeviceId);
        checks.Check("设备签名可被自身公钥验证",
            () => PolicyVerifier.VerifySignature(first.PublicKeySpki, "canonical", first.Sign("canonical")));
        checks.Check("篡改后的规范串签名验证失败",
            () => !PolicyVerifier.VerifySignature(first.PublicKeySpki, "canonical-tampered", first.Sign("canonical")));
    }

    private static void UpdateChecks(Checks checks, string root)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var spki = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
        var package = Encoding.UTF8.GetBytes("work-assistant-package-v0.2.0");
        var sha = SigningCanonical.Sha256Hex(package);
        var canonical = SigningCanonical.Update("0.2.0", sha, "https://updates.example/wa/0.2.0.zip",
            "0.1.0", "2026-11-11T00:00:00Z");
        var signature = Convert.ToBase64String(key.SignData(Encoding.UTF8.GetBytes(canonical), HashAlgorithmName.SHA256));
        var manifest = new UpdateManifest("0.2.0", sha, "https://updates.example/wa/0.2.0.zip",
            "0.1.0", "2026-11-11T00:00:00Z", "update-check-key", signature);
        var service = new UpdateService(Path.Combine(root, "updates"));

        service.Stage(manifest, package, "0.1.0", spki, "update-check-key");
        checks.Check("更新清单验签并暂存成功", () => service.HasPending);
        var failing = new RecordingInstaller();
        checks.Check("健康检查失败时回滚到上一版本",
            () => service.ApplyPending(failing, () => false) == UpdateOutcome.RolledBack
                  && failing is { Installed: true, Restored: true }
                  && !service.HasPending);
        checks.Check("健康检查失败时清理暂存目录",
            () => !Directory.Exists(Path.Combine(root, "updates", "0.2.0")));

        service.Stage(manifest, package, "0.1.0", spki, "update-check-key");
        var healthy = new RecordingInstaller();
        checks.Check("健康检查通过时安装生效",
            () => service.ApplyPending(healthy, () => true) == UpdateOutcome.Applied
                  && healthy is { Installed: true, Restored: false });
        checks.Throws<InvalidOperationException>("拒绝降级或重复安装",
            () => service.Stage(manifest, package, "0.2.0", spki, "update-check-key"));
        checks.Throws<InvalidOperationException>("更新包哈希不符时拒绝",
            () => service.Stage(manifest, Encoding.UTF8.GetBytes("tampered"), "0.1.0", spki, "update-check-key"));
        checks.Throws<InvalidOperationException>("更新清单签名无效时拒绝",
            () => service.Stage(manifest with { Signature = "AAAA" }, package, "0.1.0", spki, "update-check-key"));
        checks.Throws<InvalidOperationException>("更新清单 key id 不匹配时拒绝",
            () => service.Stage(manifest, package, "0.1.0", spki, "another-key"));
    }

    private static void CaptureAdapterChecks(Checks checks, string root)
    {
        using var signer = new TestPolicySigner();
        using var identity = DeviceIdentity.LoadOrCreate(Path.Combine(root, "capture-identity"), "tenant-capture");
        using var http = new HttpClient { BaseAddress = new Uri("https://capture.invalid") };
        var api = new DeviceApiClient(http, Guid.NewGuid().ToString(), identity);
        var session = new CaptureSession();
        var queue = new OfflineQueue(new DpapiVault(Path.Combine(root, "capture-queue"), "device-a"),
            TimeSpan.FromHours(72));
        var store = new LocalStateStore(new DpapiVault(Path.Combine(root, "capture-state"), "device-a"));
        var runtime = new AgentRuntime(session, api, queue, store, identity);
        runtime.ApplyRegistration(new RegistrationInfo("device-capture", "active",
            signer.Json(9, Array.Empty<string>(), Array.Empty<string>(), true),
            signer.PublicKeySpki, TestPolicySigner.KeyId, null, null));

        var probe = new FakeProbe(new WindowSnapshot("Code", "季度方案.docx - Word", null));
        var capture = new ForegroundActivityCapture(runtime, probe, TimeSpan.FromMinutes(1));
        var now = DateTimeOffset.UtcNow;

        checks.Check("运行时采集：未开始时采集量为 0",
            () => !capture.CaptureOnce(now).Recorded && queue.Count == 0);

        session.Start();
        checks.Check("运行时采集：允许的窗口进入离线队列",
            () => capture.CaptureOnce(now).Recorded && queue.Count == 1);
        checks.Check("运行时采集：摘要只写进程名，不含窗口标题",
            () => queue.ReadAll()[0].Draft.Summary == "Code");

        probe.Snapshot = new WindowSnapshot("WeChat", "微信", null);
        var excluded = capture.CaptureOnce(now);
        checks.Check("运行时采集：聊天应用在入队前被排除",
            () => !excluded.Recorded
                  && excluded.Reason.Contains("强制排除", StringComparison.Ordinal)
                  && queue.Count == 1);

        probe.Snapshot = new WindowSnapshot("Code", "Program.cs", null);
        session.Pause();
        checks.Check("运行时采集：暂停时采集量为 0",
            () => !capture.CaptureOnce(now).Recorded && queue.Count == 1);
        session.Stop();
    }

    private static void InstallerChecks(Checks checks, string root)
    {
        var install = Path.Combine(root, "install");
        var backups = Path.Combine(root, "install-backups");
        Directory.CreateDirectory(install);
        File.WriteAllText(Path.Combine(install, "WorkAssistant.exe"), "old");
        File.WriteAllText(Path.Combine(install, "keep.dll"), "keep");

        var package = Path.Combine(root, "package.zip");
        using (var archive = ZipFile.Open(package, ZipArchiveMode.Create))
        {
            WriteEntry(archive, "WorkAssistant.exe", "new");
            WriteEntry(archive, "plugins/plugin.dll", "plugin");
        }

        var installer = new ZipPackageInstaller(install, backups);
        installer.Install(package);
        checks.Check("ZIP 安装器覆盖旧文件并落地新增文件",
            () => File.ReadAllText(Path.Combine(install, "WorkAssistant.exe")) == "new"
                  && File.Exists(Path.Combine(install, "plugins", "plugin.dll")));

        installer.RestorePrevious();
        checks.Check("回滚恢复旧文件",
            () => File.ReadAllText(Path.Combine(install, "WorkAssistant.exe")) == "old"
                  && File.ReadAllText(Path.Combine(install, "keep.dll")) == "keep");
        checks.Check("回滚删除本次新增文件",
            () => !File.Exists(Path.Combine(install, "plugins", "plugin.dll")));

        var slip = Path.Combine(root, "slip.zip");
        using (var archive = ZipFile.Open(slip, ZipArchiveMode.Create))
        {
            WriteEntry(archive, "../escape.txt", "escape");
        }

        var guarded = new ZipPackageInstaller(install, backups);
        checks.Throws<InvalidDataException>("更新包目录穿越条目被拒绝", () => guarded.Install(slip));
        checks.Check("目录穿越被拒绝后安装目录不变",
            () => File.ReadAllText(Path.Combine(install, "WorkAssistant.exe")) == "old"
                  && !File.Exists(Path.Combine(root, "escape.txt")));
    }

    private static void WriteEntry(ZipArchive archive, string name, string content)
    {
        var entry = archive.CreateEntry(name);
        using var writer = new StreamWriter(entry.Open());
        writer.Write(content);
    }

    private static bool Draft(CaptureSession session, PolicySnapshot policy, DateTimeOffset now)
    {
        return Draft(session, policy, now, out _);
    }

    private static bool Draft(CaptureSession session, PolicySnapshot policy, DateTimeOffset now,
        out SegmentDraft? draft)
    {
        return CaptureGate.TryDraft(session, policy, now, now.AddMinutes(1), "窗口片段", out draft);
    }

    private static void TryDelete(string directory)
    {
        try
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, recursive: true);
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Console.WriteLine($"WARN 临时目录未能删除: {directory}");
        }
    }

    private sealed class RecordingInstaller : IUpdateInstaller
    {
        public bool Installed { get; private set; }

        public bool Restored { get; private set; }

        public void Install(string packagePath) => Installed = true;

        public void RestorePrevious() => Restored = true;
    }

    private sealed class FakeProbe : IWindowProbe
    {
        public FakeProbe(WindowSnapshot snapshot)
        {
            Snapshot = snapshot;
        }

        public WindowSnapshot Snapshot { get; set; }

        public WindowSnapshot ReadForegroundWindow() => Snapshot;

        public IReadOnlyList<WindowSnapshot> ReadVisibleWindows() => new[] { Snapshot };
    }
}
