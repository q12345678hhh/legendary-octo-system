using System.Security.Cryptography;
using System.Text;

namespace WorkAssistant.Storage;

/// <summary>
/// DPAPI 本地加密仓库。
///
/// <p>所有文件都按当前 Windows 用户加密，附加熵包含设备 ID：换用户或换设备都无法解密。
/// 写入走「临时文件 + flush + 原子替换」，崩溃最多留下一个可识别的临时文件。
/// </summary>
public sealed class DpapiVault
{
    private readonly byte[] entropy;

    public DpapiVault(string rootDirectory, string deviceId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        RootDirectory = Path.GetFullPath(rootDirectory);
        entropy = SHA256.HashData(Encoding.UTF8.GetBytes($"work-assistant|{deviceId}"));
        Directory.CreateDirectory(RootDirectory);
    }

    public string RootDirectory { get; }

    public void Write(string relativePath, byte[] plaintext)
    {
        var target = Resolve(relativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        var temporary = target + ".tmp";
        var ciphertext = Protect(plaintext);
        using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            stream.Write(ciphertext);
            stream.Flush(flushToDisk: true);
        }

        File.Move(temporary, target, overwrite: true);
    }

    public byte[] Read(string relativePath)
    {
        return Unprotect(File.ReadAllBytes(Resolve(relativePath)));
    }

    public bool Exists(string relativePath) => File.Exists(Resolve(relativePath));

    public void Delete(string relativePath)
    {
        var target = Resolve(relativePath);
        if (File.Exists(target))
        {
            File.Delete(target);
        }
    }

    public byte[] Protect(byte[] plaintext)
    {
        return ProtectedData.Protect(plaintext, entropy, DataProtectionScope.CurrentUser);
    }

    public byte[] Unprotect(byte[] ciphertext)
    {
        return ProtectedData.Unprotect(ciphertext, entropy, DataProtectionScope.CurrentUser);
    }

    private string Resolve(string relativePath)
    {
        var combined = Path.GetFullPath(Path.Combine(RootDirectory, relativePath));
        if (!combined.StartsWith(RootDirectory, StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException($"路径越出加密仓库: {relativePath}", nameof(relativePath));
        }

        return combined;
    }
}
