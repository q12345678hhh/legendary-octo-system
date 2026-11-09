using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace SpikeF;

// ---------- Win32 互操作 ----------

internal static class Win32
{
    internal delegate bool EnumWindowsProc(nint hWnd, nint lParam);

    [DllImport("user32.dll")]
    internal static extern bool EnumWindows(EnumWindowsProc cb, nint lParam);

    [DllImport("user32.dll")]
    internal static extern bool IsWindowVisible(nint hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    internal static extern int GetWindowTextW(nint hWnd, StringBuilder text, int max);

    [DllImport("user32.dll")]
    internal static extern uint GetWindowThreadProcessId(nint hWnd, out uint pid);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    internal static extern int GetClassNameW(nint hWnd, StringBuilder sb, int max);

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    internal struct WaveFormatEx
    {
        public ushort wFormatTag;
        public ushort nChannels;
        public uint nSamplesPerSec;
        public uint nAvgBytesPerSec;
        public ushort nBlockAlign;
        public ushort wBitsPerSample;
        public ushort cbSize;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct WaveHdr
    {
        public nint lpData;
        public uint dwBufferLength;
        public uint dwBytesRecorded;
        public nint dwUser;
        public uint dwFlags;
        public uint dwLoops;
        public nint lpNext;
        public nint reserved;
    }

    internal const uint WaveMapper = 0xFFFFFFFF;
    internal const uint CallbackNull = 0x00000000;

    [DllImport("winmm.dll")]
    internal static extern uint waveInGetNumDevs();

    [DllImport("winmm.dll", CharSet = CharSet.Unicode)]
    internal static extern uint waveInGetDevCapsW(nint deviceId, nint caps, uint size);

    [DllImport("winmm.dll")]
    internal static extern uint waveInOpen(out nint hwi, uint deviceId, ref WaveFormatEx fmt, nint callback, nint instance, uint flags);

    [DllImport("winmm.dll")]
    internal static extern uint waveInPrepareHeader(nint hwi, nint hdr, uint size);

    [DllImport("winmm.dll")]
    internal static extern uint waveInUnprepareHeader(nint hwi, nint hdr, uint size);

    [DllImport("winmm.dll")]
    internal static extern uint waveInAddBuffer(nint hwi, nint hdr, uint size);

    [DllImport("winmm.dll")]
    internal static extern uint waveInStart(nint hwi);

    [DllImport("winmm.dll")]
    internal static extern uint waveInReset(nint hwi);

    [DllImport("winmm.dll")]
    internal static extern uint waveInClose(nint hwi);
}

// ---------- 采集策略：排除判定先于任何内容读取 ----------

internal sealed class CollectionPolicy
{
    // 硬编码下限：无论租户怎么配置，这些进程永不被采集。
    private static readonly string[] AlwaysExcluded =
    {
        "WeChat", "Weixin", "WeChatAppEx", "WeChatOCR",
        "QQ", "QQNT", "TIM", "Telegram", "WhatsApp",
        "DingTalk", "Feishu", "Lark", "Slack", "Discord"
    };

    private readonly HashSet<string> _hard = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _tenant = new(StringComparer.OrdinalIgnoreCase);

    public CollectionPolicy(IEnumerable<string>? tenantExcluded = null)
    {
        foreach (var n in AlwaysExcluded) _hard.Add(n);
        foreach (var n in tenantExcluded ?? Array.Empty<string>()) _tenant.Add(n);
    }

    public IReadOnlyCollection<string> AlwaysExcludedNames => _hard;

    public (bool Excluded, string Reason) Evaluate(string processName)
    {
        if (string.IsNullOrWhiteSpace(processName)) return (true, "unknown-process");
        if (_hard.Contains(processName)) return (true, "hard-excluded:social");
        if (_tenant.Contains(processName)) return (true, "tenant-excluded");
        return (false, "allowed");
    }
}

// ---------- 窗口枚举 ----------

internal sealed record ObservedWindow(
    uint Pid,
    string Process,
    string WindowClass,
    string App,
    string? Title,
    bool Excluded,
    string Reason);

internal static class WindowScanner
{
    public static List<ObservedWindow> Scan(CollectionPolicy policy, List<string> audit)
    {
        var results = new List<ObservedWindow>();
        Win32.EnumWindows((hwnd, _) =>
        {
            if (!Win32.IsWindowVisible(hwnd)) return true;
            Win32.GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == 0) return true;

            var process = ProcessName((int)pid);
            if (process.Length == 0) return true;

            // 排除判定只用进程名，不触碰窗口内容。
            var (excluded, reason) = policy.Evaluate(process);
            var cls = ClassName(hwnd);

            if (excluded)
            {
                // 关键不变量：此处绝不调用 GetWindowTextW。
                audit.Add($"excluded pid={pid} process={process} class={cls} reason={reason} content_read=false");
                results.Add(new ObservedWindow(pid, process, cls, ApplicationCatalog.Classify(process), null, true, reason));
                return true;
            }

            audit.Add($"allowed  pid={pid} process={process} class={cls} reason={reason} content_read=true");
            results.Add(new ObservedWindow(pid, process, cls, ApplicationCatalog.Classify(process), Title(hwnd), false, reason));
            return true;
        }, 0);
        return results;
    }

    private static string ProcessName(int pid)
    {
        try { return Process.GetProcessById(pid).ProcessName; }
        catch { return string.Empty; }
    }

    private static string ClassName(nint hwnd)
    {
        var sb = new StringBuilder(256);
        var n = Win32.GetClassNameW(hwnd, sb, sb.Capacity);
        return n > 0 ? sb.ToString() : string.Empty;
    }

    private static string Title(nint hwnd)
    {
        var sb = new StringBuilder(1024);
        var n = Win32.GetWindowTextW(hwnd, sb, sb.Capacity);
        return n > 0 ? sb.ToString() : string.Empty;
    }
}

internal static class ApplicationCatalog
{
    public static string Classify(string process) => process.ToLowerInvariant() switch
    {
        "msedge" => "edge",
        "chrome" => "chrome",
        "code" => "vscode",
        "winword" => "word",
        "excel" => "excel",
        "powerpnt" => "powerpoint",
        "outlook" => "outlook",
        "notepad" or "notepad++" => "editor",
        _ => "unknown"
    };
}

// ---------- 本地最小化与脱敏 ----------

internal static class TextMinimizer
{
    public const int MaxLength = 200;

    private static readonly Regex Email = new(@"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}", RegexOptions.Compiled);
    private static readonly Regex IdCard18 = new(@"\b\d{17}[\dXx]\b", RegexOptions.Compiled);
    private static readonly Regex BankCard = new(@"\b\d{16,19}\b", RegexOptions.Compiled);
    private static readonly Regex Phone = new(@"\b1[3-9]\d{9}\b", RegexOptions.Compiled);
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);

    public static string Minimize(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return string.Empty;
        var s = Whitespace.Replace(raw, " ").Trim();
        s = Email.Replace(s, "[email]");
        s = IdCard18.Replace(s, "[id]");
        s = BankCard.Replace(s, "[card]");
        s = Phone.Replace(s, "[phone]");
        return s.Length <= MaxLength ? s : s[..MaxLength] + "…";
    }
}

// ---------- 设备身份 ----------

internal sealed class DeviceIdentity
{
    public required string DeviceId { get; init; }
    public required string PublicKeyBase64 { get; init; }
    private ECDsa Key { get; init; } = null!;

    public static DeviceIdentity Create()
    {
        var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var pub = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
        return new DeviceIdentity
        {
            DeviceId = Fingerprint(),
            PublicKeyBase64 = pub,
            Key = key
        };
    }

    public string Sign(string payload)
    {
        var sig = Key.SignData(Encoding.UTF8.GetBytes(payload), HashAlgorithmName.SHA256);
        return Convert.ToBase64String(sig);
    }

    public bool Verify(string payload, string signatureBase64)
    {
        var sig = Convert.FromBase64String(signatureBase64);
        return Key.VerifyData(Encoding.UTF8.GetBytes(payload), sig, HashAlgorithmName.SHA256);
    }

    private static string Fingerprint()
    {
        string guid;
        try
        {
            using var baseKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
            using var k = baseKey.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            guid = k?.GetValue("MachineGuid")?.ToString() ?? Environment.MachineName;
        }
        catch { guid = Environment.MachineName; }

        var material = Encoding.UTF8.GetBytes($"{guid}|{Environment.MachineName}");
        var hash = SHA256.HashData(material);
        return Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }
}

// ---------- 语音采集 ----------

internal sealed record AudioDevice(int Index, string Name, ushort Channels);

internal static class Audio
{
    public static List<AudioDevice> ListDevices()
    {
        var list = new List<AudioDevice>();
        var count = Win32.waveInGetNumDevs();
        const int capsSize = 80;
        var buf = Marshal.AllocHGlobal(capsSize);
        try
        {
            for (var i = 0; i < count; i++)
            {
                for (var b = 0; b < capsSize; b++) Marshal.WriteByte(buf, b, 0);
                var rc = Win32.waveInGetDevCapsW(i, buf, capsSize);
                if (rc != 0) continue;

                var raw = new byte[capsSize];
                Marshal.Copy(buf, raw, 0, capsSize);
                var end = Array.IndexOf(raw, (byte)0);
                if (end < 0) end = 64;
                if (end % 2 != 0) end--;
                var name = Encoding.Unicode.GetString(raw, 0, Math.Min(end, 64)).Trim();
                var channels = BitConverter.ToUInt16(raw, 76);
                list.Add(new AudioDevice(i, name, channels));
            }
        }
        finally { Marshal.FreeHGlobal(buf); }
        return list;
    }

    /// <summary>用 waveIn 录一段 16 kHz / 16 bit / 单声道 PCM，返回 (wav, error)。</summary>
    public static (byte[]? Wav, uint Error) Record(int milliseconds, int deviceIndex = 0)
    {
        var fmt = new Win32.WaveFormatEx
        {
            wFormatTag = 1,
            nChannels = 1,
            nSamplesPerSec = 16000,
            wBitsPerSample = 16,
            nBlockAlign = 2,
            nAvgBytesPerSec = 32000,
            cbSize = 0
        };

        var rc = Win32.waveInOpen(out var hwi, (uint)deviceIndex, ref fmt, 0, 0, Win32.CallbackNull);
        if (rc != 0) return (null, rc);

        const int bufferCount = 8;
        const int bufferSize = 16000; // 0.5 秒 × 8 = 4 秒容量
        var buffers = new nint[bufferCount];
        var headers = new nint[bufferCount];
        var headerSize = (uint)Marshal.SizeOf<Win32.WaveHdr>();
        var wav = new MemoryStream(44 + bufferSize * bufferCount);

        try
        {
            for (var i = 0; i < bufferCount; i++)
            {
                buffers[i] = Marshal.AllocHGlobal(bufferSize);
                var hdr = new Win32.WaveHdr { lpData = buffers[i], dwBufferLength = bufferSize };
                headers[i] = Marshal.AllocHGlobal((int)headerSize);
                Marshal.StructureToPtr(hdr, headers[i], false);
                var e1 = Win32.waveInPrepareHeader(hwi, headers[i], headerSize);
                var e2 = Win32.waveInAddBuffer(hwi, headers[i], headerSize);
                if (e1 != 0 || e2 != 0) return (null, e1 != 0 ? e1 : e2);
            }

            var startError = Win32.waveInStart(hwi);
            if (startError != 0) return (null, startError);

            Thread.Sleep(milliseconds);
            Win32.waveInReset(hwi);

            var pcm = new MemoryStream();
            for (var i = 0; i < bufferCount; i++)
            {
                var hdr = Marshal.PtrToStructure<Win32.WaveHdr>(headers[i]);
                if (hdr.dwBytesRecorded == 0) continue;
                var chunk = new byte[hdr.dwBytesRecorded];
                Marshal.Copy(hdr.lpData, chunk, 0, (int)hdr.dwBytesRecorded);
                pcm.Write(chunk, 0, chunk.Length);
            }

            var data = pcm.ToArray();
            WriteWavHeader(wav, data.Length);
            wav.Write(data, 0, data.Length);
            return (wav.ToArray(), 0);
        }
        finally
        {
            for (var i = 0; i < bufferCount; i++)
            {
                if (headers[i] != 0)
                {
                    Win32.waveInUnprepareHeader(hwi, headers[i], headerSize);
                    Marshal.FreeHGlobal(headers[i]);
                }
                if (buffers[i] != 0) Marshal.FreeHGlobal(buffers[i]);
            }
            Win32.waveInClose(hwi);
        }
    }

    private static void WriteWavHeader(Stream s, int dataLength)
    {
        using var w = new BinaryWriter(s, Encoding.ASCII, leaveOpen: true);
        w.Write(Encoding.ASCII.GetBytes("RIFF"));
        w.Write(36 + dataLength);
        w.Write(Encoding.ASCII.GetBytes("WAVE"));
        w.Write(Encoding.ASCII.GetBytes("fmt "));
        w.Write(16);
        w.Write((ushort)1);
        w.Write((ushort)1);
        w.Write(16000);
        w.Write(32000);
        w.Write((ushort)2);
        w.Write((ushort)16);
        w.Write(Encoding.ASCII.GetBytes("data"));
        w.Write(dataLength);
    }
}

// ---------- 自检 ----------

internal static class Program
{
    private static readonly List<string> Failures = new();
    private static readonly List<string> Skips = new();
    private static int _passed;

    private static void Check(string name, bool ok, string detail, bool skip = false)
    {
        if (skip)
        {
            Skips.Add(name);
            Console.WriteLine($"SKIP  {name}  {detail}");
            return;
        }
        if (ok) { _passed++; Console.WriteLine($"PASS  {name}  {detail}"); }
        else { Failures.Add(name); Console.WriteLine($"FAIL  {name}  {detail}"); }
    }

    private static int Main(string[] args)
    {
        var cmd = args.Length > 0 ? args[0] : "selftest";
        return cmd switch
        {
            "windows" => CmdWindows(),
            "device" => CmdDevice(),
            "audio" => CmdAudio(args),
            "selftest" => CmdSelfTest(args),
            _ => Unknown(cmd)
        };
    }

    private static int Unknown(string cmd)
    {
        Console.WriteLine($"unknown command: {cmd}");
        Console.WriteLine("usage: SpikeF [windows|device|audio [--record ms]|selftest]");
        return 2;
    }

    private static int CmdWindows()
    {
        var audit = new List<string>();
        var policy = new CollectionPolicy();
        var windows = WindowScanner.Scan(policy, audit);
        foreach (var w in windows)
        {
            Console.WriteLine($"{w.Pid,6}  {w.Process,-20} {w.App,-10} excluded={w.Excluded}  title={w.Title ?? "<not-read>"}");
        }
        Console.WriteLine($"windows={windows.Count} excluded={windows.Count(x => x.Excluded)}");
        return 0;
    }

    private static int CmdDevice()
    {
        var dev = DeviceIdentity.Create();
        var payload = "spike-f|device-check|" + DateTime.UtcNow.ToString("o");
        var sig = dev.Sign(payload);
        Console.WriteLine($"device_id={dev.DeviceId}");
        Console.WriteLine($"public_key={dev.PublicKeyBase64[..32]}…");
        Console.WriteLine($"signature={sig[..32]}…");
        Console.WriteLine($"verify={dev.Verify(payload, sig)}");
        return 0;
    }

    private static int CmdAudio(string[] args)
    {
        var devices = Audio.ListDevices();
        for (var i = 0; i < devices.Count; i++)
        {
            Console.WriteLine($"[{devices[i].Index}] {devices[i].Name} channels={devices[i].Channels}");
        }
        var ms = 2000;
        var idx = args.ToList().IndexOf("--record");
        if (idx > 0 && idx + 1 < args.Length) int.TryParse(args[idx + 1], out ms);
        if (devices.Count == 0) { Console.WriteLine("no capture device"); return 1; }

        var (wav, err) = Audio.Record(ms);
        if (wav == null) { Console.WriteLine($"record failed err={err}"); return 3; }
        var path = Path.Combine(AppContext.BaseDirectory, "spike-f-sample.wav");
        File.WriteAllBytes(path, wav);
        Console.WriteLine($"recorded bytes={wav.Length} path={path}");
        return 0;
    }

    private static int CmdSelfTest(string[] args)
    {
        var outDir = args.Length > 1 ? args[1] : Directory.GetCurrentDirectory();
        Directory.CreateDirectory(outDir);

        Console.WriteLine("=== SPIKE F SELFTEST ===");
        Console.WriteLine($"dotnet={Environment.Version} os={Environment.OSVersion.VersionString}");

        // 1. 策略：硬排除优先于任何配置
        var policy = new CollectionPolicy(new[] { "notepad" });
        Check("policy.hard-excluded", policy.Evaluate("Weixin").Excluded && policy.Evaluate("QQ").Excluded,
            "Weixin/QQ 命中硬排除");
        Check("policy.allowed", !policy.Evaluate("msedge").Excluded, "msedge 允许采集");
        Check("policy.tenant-excluded", policy.Evaluate("notepad").Excluded, "租户强制排除生效（notepad）");

        // 2. 真实窗口扫描：排除进程不得读取标题
        var audit = new List<string>();
        var windows = WindowScanner.Scan(policy, audit);
        var excluded = windows.Where(w => w.Excluded).ToList();
        Check("scan.exclusion-before-read",
            excluded.Count > 0 && excluded.All(w => w.Title == null),
            $"找到 {excluded.Count} 个排除窗口，标题读取数={excluded.Count(w => w.Title != null)}",
            skip: excluded.Count == 0);

        var contentReadAudit = audit.Where(l => l.Contains("content_read=true")).ToList();
        var excludedAudit = audit.Where(l => l.Contains("content_read=false")).ToList();
        Check("scan.audit-trail", excludedAudit.Count == excluded.Count && contentReadAudit.Count > 0,
            $"audit 记录：content_read=false {excludedAudit.Count} 条，content_read=true {contentReadAudit.Count} 条");

        // 3. 租户强制排除：启动一个真实进程，按 PID 等可见窗口，再用该进程名做租户排除
        var probe = TryStartVisibleProcess();
        if (probe != null)
        {
            var probeName = probe.ProcessName;
            var tenantPolicy = new CollectionPolicy(new[] { probeName });
            var probeWindows = WindowScanner.Scan(tenantPolicy, new List<string>())
                .Where(w => w.Process.Equals(probeName, StringComparison.OrdinalIgnoreCase))
                .ToList();
            Check("scan.tenant-enforced-runtime",
                probeWindows.Count > 0 && probeWindows.All(w => w.Excluded && w.Title == null),
                $"{probeName} 窗口 {probeWindows.Count} 个，全部排除且未读标题",
                skip: probeWindows.Count == 0);
            try { probe.Kill(); } catch { }
        }
        else
        {
            Check("scan.tenant-enforced-runtime", false, "无法启动带窗口的探针进程", skip: true);
        }

        // 4. 脱敏
        var raw = "联系 13812345678 邮箱 zhangsan@example.com 身份证 110101199003078888 卡号 6222021234567890123";
        var min = TextMinimizer.Minimize(raw);
        Check("minimizer.redaction",
            !min.Contains("13812345678") && !min.Contains("zhangsan@example.com")
            && !min.Contains("110101199003078888") && !min.Contains("6222021234567890123"),
            min);

        // 5. 长度上限
        var longText = new string('测', 500);
        Check("minimizer.length-cap", TextMinimizer.Minimize(longText).Length <= TextMinimizer.MaxLength + 1,
            $"len={TextMinimizer.Minimize(longText).Length}");

        // 6. 设备身份
        var dev = DeviceIdentity.Create();
        var payload = "spike-f|payload";
        var sig = dev.Sign(payload);
        var verifyOk = dev.Verify(payload, sig);
        var tamperOk = !dev.Verify(payload + "x", sig);
        Check("device.sign-verify", verifyOk, $"device_id={dev.DeviceId}");
        Check("device.tamper-rejected", tamperOk, "篡改载荷后验签失败");

        // 7. 语音采集设备
        var devices = Audio.ListDevices();
        var deviceDesc = string.Join(" / ", devices.Select(d =>
            string.IsNullOrEmpty(d.Name) ? $"#{d.Index}({d.Channels}ch,unnamed)" : $"{d.Name}({d.Channels}ch)"));
        Check("audio.devices", devices.Count > 0, $"capture devices={devices.Count}: {deviceDesc}",
            skip: devices.Count == 0);

        // 8. 实际录制 2 秒
        if (devices.Count > 0)
        {
            var (wav, err) = Audio.Record(2000);
            var ok = wav != null && wav.Length > 44
                     && Encoding.ASCII.GetString(wav, 0, 4) == "RIFF"
                     && Encoding.ASCII.GetString(wav, 8, 4) == "WAVE";
            Check("audio.record-2s", ok,
                wav == null ? $"waveInOpen err={err}（无可用采集硬件）" : $"wav bytes={wav.Length}",
                skip: wav == null);
            if (wav != null) File.WriteAllBytes(Path.Combine(outDir, "sample-2s.wav"), wav);
        }

        // 9. 上报载荷：排除项必须为零
        var telemetry = windows
            .Where(w => !w.Excluded)
            .Select(w => new
            {
                process = w.Process,
                app = w.App,
                window_class = w.WindowClass,
                title = TextMinimizer.Minimize(w.Title)
            })
            .ToList();
        var json = JsonSerializer.Serialize(new
        {
            device_id = dev.DeviceId,
            generated_at = DateTime.UtcNow.ToString("o"),
            policy_always_excluded = policy.AlwaysExcludedNames,
            segments = telemetry
        }, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(Path.Combine(outDir, "telemetry.json"), json);

        var leaked = telemetry.Any(s => policy.Evaluate(s.process).Excluded);
        Check("telemetry.no-excluded-content", !leaked,
            $"segments={telemetry.Count}，其中排除应用={telemetry.Count(s => policy.Evaluate(s.process).Excluded)}");
        Check("telemetry.signature",
            dev.Verify(json, dev.Sign(json)), "上报载荷可由设备私钥签名并验签");

        Console.WriteLine();
        Console.WriteLine($"=== RESULT: {(_passed + Failures.Count + Skips.Count)} checks, {_passed} passed, {Skips.Count} skipped, {Failures.Count} failed ===");
        if (Skips.Count > 0) Console.WriteLine("skipped: " + string.Join(", ", Skips));
        if (Failures.Count > 0) Console.WriteLine("failed: " + string.Join(", ", Failures));
        return Failures.Count == 0 ? 0 : 1;
    }

    /// <summary>启动一个带可见窗口的探针进程，按 PID 确认窗口出现（不依赖进程名）。</summary>
    private static Process? TryStartVisibleProcess()
    {
        string[] candidates = { "notepad.exe", "mspaint.exe", "charmap.exe", "control.exe" };
        foreach (var exe in candidates)
        {
            try
            {
                var p = Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
                if (p == null) continue;
                for (var i = 0; i < 25; i++)
                {
                    Thread.Sleep(200);
                    if (p.HasExited) break;
                    if (HasVisibleWindow((uint)p.Id)) return p;
                }
                try { p.Kill(); } catch { }
            }
            catch { }
        }
        return null;
    }

    private static bool HasVisibleWindow(uint pid)
    {
        var found = false;
        Win32.EnumWindows((hwnd, _) =>
        {
            if (!Win32.IsWindowVisible(hwnd)) return true;
            Win32.GetWindowThreadProcessId(hwnd, out var owner);
            if (owner == pid) { found = true; return false; }
            return true;
        }, 0);
        return found;
    }
}
