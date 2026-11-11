using System.Security.Cryptography;

namespace WorkAssistant.Device.Checks;

/// <summary>
/// 本机取证用的密钥生成：策略签名密钥与更新签名密钥各一对，
/// 只写入显式指定的目录（由脚本限定在 var/ 下，不进入版本库）。
/// </summary>
internal static class KeyGenerator
{
    public static int Run(string directory)
    {
        Directory.CreateDirectory(directory);
        Write(directory, "policy");
        Write(directory, "update");
        Console.WriteLine($"KEYS {Path.GetFullPath(directory)}");
        return 0;
    }

    private static void Write(string directory, string name)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        File.WriteAllText(Path.Combine(directory, name + ".key"),
            Convert.ToBase64String(key.ExportPkcs8PrivateKey()));
        File.WriteAllText(Path.Combine(directory, name + ".pub"),
            Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()));
    }
}
