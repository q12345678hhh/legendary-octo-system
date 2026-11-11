using System.Security.Cryptography;
using System.Text.Json;
using WorkAssistant.Core;

namespace WorkAssistant.Device;

/// <summary>已签名的更新清单（与 OpenAPI 的 UpdateManifest 字段一致）。</summary>
public sealed record UpdateManifest(
    string Version,
    string PackageSha256,
    string PackageUrl,
    string MinSupportedVersion,
    string ReleasedAt,
    string KeyId,
    string Signature);

public enum UpdateOutcome
{
    NoPending,
    Applied,
    RolledBack,
}

/// <summary>真实安装器在发布工作包接入；骨架用可注入实现验证回滚路径。</summary>
public interface IUpdateInstaller
{
    void Install(string packagePath);

    void RestorePrevious();
}

/// <summary>
/// 更新验证与回滚：验签 → 校验包哈希 → 暂存 → 安装 → 健康检查 → 失败恢复上一版本。
/// 任一步失败都不得让客户端停留在半安装状态。
/// </summary>
public sealed class UpdateService
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    private readonly string updatesDirectory;

    public UpdateService(string rootDirectory)
    {
        updatesDirectory = Path.Combine(Path.GetFullPath(rootDirectory), "updates");
        Directory.CreateDirectory(updatesDirectory);
    }

    public bool HasPending => File.Exists(PendingPath);

    /// <summary>验签、校验哈希、拒绝降级，然后原子暂存。</summary>
    public void Stage(UpdateManifest manifest, byte[] packageBytes, string currentVersion,
        string pinnedPublicKeySpki, string pinnedKeyId)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        ArgumentNullException.ThrowIfNull(packageBytes);
        if (!string.Equals(manifest.KeyId, pinnedKeyId, StringComparison.Ordinal))
        {
            throw new InvalidOperationException($"更新清单 key id 不匹配: {manifest.KeyId}");
        }

        var canonical = SigningCanonical.Update(manifest.Version, manifest.PackageSha256,
            manifest.PackageUrl, manifest.MinSupportedVersion, manifest.ReleasedAt);
        if (!PolicyVerifier.VerifySignature(pinnedPublicKeySpki, canonical, manifest.Signature))
        {
            throw new InvalidOperationException("更新清单签名无效");
        }

        var actual = Convert.ToHexString(SHA256.HashData(packageBytes)).ToLowerInvariant();
        if (!string.Equals(actual, manifest.PackageSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("更新包哈希与清单不一致");
        }

        if (CompareVersions(manifest.Version, currentVersion) <= 0)
        {
            throw new InvalidOperationException($"拒绝降级或重复安装: {manifest.Version} <= {currentVersion}");
        }

        var versionDirectory = Path.Combine(updatesDirectory, Sanitize(manifest.Version));
        Directory.CreateDirectory(versionDirectory);
        File.WriteAllBytes(Path.Combine(versionDirectory, "package.bin"), packageBytes);
        File.WriteAllText(PendingPath, JsonSerializer.Serialize(manifest, Json));
    }

    public UpdateOutcome ApplyPending(IUpdateInstaller installer, Func<bool> healthCheck)
    {
        ArgumentNullException.ThrowIfNull(installer);
        ArgumentNullException.ThrowIfNull(healthCheck);
        if (!File.Exists(PendingPath))
        {
            return UpdateOutcome.NoPending;
        }

        var manifest = JsonSerializer.Deserialize<UpdateManifest>(File.ReadAllText(PendingPath), Json)
            ?? throw new InvalidDataException("更新清单损坏");
        var packagePath = Path.Combine(updatesDirectory, Sanitize(manifest.Version), "package.bin");
        try
        {
            installer.Install(packagePath);
            if (healthCheck())
            {
                Cleanup(manifest.Version);
                return UpdateOutcome.Applied;
            }
        }
        catch (Exception)
        {
            installer.RestorePrevious();
            Cleanup(manifest.Version);
            throw;
        }

        installer.RestorePrevious();
        Cleanup(manifest.Version);
        return UpdateOutcome.RolledBack;
    }

    private void Cleanup(string version)
    {
        File.Delete(PendingPath);
        var directory = Path.Combine(updatesDirectory, Sanitize(version));
        if (Directory.Exists(directory))
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private string PendingPath => Path.Combine(updatesDirectory, "pending.json");

    private static string Sanitize(string version)
    {
        return string.Concat(version.Select(ch => char.IsLetterOrDigit(ch) || ch is '.' or '-' or '_' ? ch : '_'));
    }

    internal static int CompareVersions(string left, string right)
    {
        var a = left.Split('.', '-');
        var b = right.Split('.', '-');
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var x = i < a.Length && int.TryParse(a[i], out var parsedX) ? parsedX : 0;
            var y = i < b.Length && int.TryParse(b[i], out var parsedY) ? parsedY : 0;
            if (x != y)
            {
                return x.CompareTo(y);
            }
        }

        return 0;
    }
}
