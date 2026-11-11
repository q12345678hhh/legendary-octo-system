using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;
using WorkAssistant.Storage;

namespace WorkAssistant.Device;

/// <summary>
/// 设备身份：ECDSA P-256 私钥只存在 DPAPI 加密文件中，公钥随注册上送。
/// 设备 ID 由 MachineGuid + tenantId 派生，便于同机重装后仍能识别为同一物理设备。
/// </summary>
public sealed class DeviceIdentity : IDisposable
{
    private const string KeyPath = "device.key";
    private const string MachineIdPath = "machine-id";

    private readonly ECDsa key;

    private DeviceIdentity(string deviceId, ECDsa key)
    {
        DeviceId = deviceId;
        this.key = key;
        PublicKeySpki = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
    }

    public string DeviceId { get; }

    public string PublicKeySpki { get; }

    /// <summary>
    /// 打开或创建本机设备身份。安装标识与设备密钥分两个熵域：
    /// 先解析设备 ID，再用设备 ID 派生的熵加密私钥，读取密钥必须同时具备本机与本租户。
    /// </summary>
    public static DeviceIdentity LoadOrCreate(string rootDirectory, string tenantId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(tenantId);

        var bootstrap = new DpapiVault(rootDirectory, "work-assistant-install");
        var deviceId = ResolveDeviceId(bootstrap, tenantId);
        // 私钥按租户分目录存放：同一台机器切换租户不会覆盖或误用上一租户的密钥
        var tenantSegment = Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(tenantId))).ToLowerInvariant()[..16];
        var vault = new DpapiVault(Path.Combine(rootDirectory, "devices", tenantSegment), deviceId);

        var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        if (vault.Exists(KeyPath))
        {
            key.ImportPkcs8PrivateKey(vault.Read(KeyPath), out _);
        }
        else
        {
            vault.Write(KeyPath, key.ExportPkcs8PrivateKey());
        }

        return new DeviceIdentity(deviceId, key);
    }

    public string Sign(string canonical)
    {
        return Convert.ToBase64String(key.SignData(
            Encoding.UTF8.GetBytes(canonical), HashAlgorithmName.SHA256));
    }

    /// <summary>同一台机器 + 同一租户得到同一设备 ID；系统重装后 MachineGuid 变化即视为新设备。</summary>
    public static string ResolveDeviceId(DpapiVault vault, string tenantId)
    {
        var machine = TryReadMachineGuid() ?? ReadFallbackMachineId(vault);
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes($"work-assistant-device|{machine}|{tenantId}"));
        return new Guid(hash.AsSpan(0, 16)).ToString("D");
    }

    public void Dispose() => key.Dispose();

    private static string? TryReadMachineGuid()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            return key?.GetValue("MachineGuid") as string;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or IOException or System.Security.SecurityException)
        {
            return null;
        }
    }

    private static string ReadFallbackMachineId(DpapiVault vault)
    {
        if (vault.Exists(MachineIdPath))
        {
            return Encoding.UTF8.GetString(vault.Read(MachineIdPath));
        }

        var generated = Guid.NewGuid().ToString("D");
        vault.Write(MachineIdPath, Encoding.UTF8.GetBytes(generated));
        return generated;
    }
}
