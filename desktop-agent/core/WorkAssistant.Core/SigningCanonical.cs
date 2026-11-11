using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace WorkAssistant.Core;

/// <summary>
/// 签名规范串：必须与服务端 <c>CanonicalForms</c> 逐字一致。
/// 列表按序数排序（区分大小写），时间用调用方给出的原字符串，不做二次格式化。
/// </summary>
public static class SigningCanonical
{
    public static string Request(string method, string path, string timestamp, string body)
    {
        return string.Join(
            "\n",
            method.ToUpperInvariant(),
            path,
            timestamp,
            Sha256Hex(Encoding.UTF8.GetBytes(body ?? string.Empty)));
    }

    public static string Policy(int version, string issuedAt, IEnumerable<string> processes,
        IEnumerable<string> titleKeywords, bool uploadEnabled)
    {
        return string.Join(
            "|",
            version.ToString(CultureInfo.InvariantCulture),
            issuedAt,
            JoinSorted(processes),
            JoinSorted(titleKeywords),
            uploadEnabled ? "true" : "false");
    }

    public static string Update(string version, string packageSha256, string packageUrl,
        string minSupportedVersion, string releasedAt)
    {
        return string.Join("|", version, packageSha256, packageUrl, minSupportedVersion, releasedAt);
    }

    public static string Sha256Hex(byte[] bytes)
    {
        return Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    }

    private static string JoinSorted(IEnumerable<string> values)
    {
        var sorted = values.ToList();
        sorted.Sort(StringComparer.Ordinal);
        return string.Join(",", sorted);
    }
}
