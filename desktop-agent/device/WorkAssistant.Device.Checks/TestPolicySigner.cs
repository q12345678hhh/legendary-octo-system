using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using WorkAssistant.Core;

namespace WorkAssistant.Device.Checks;

/// <summary>离线自检用的一次性策略签名器，密钥只存在于内存。</summary>
internal sealed class TestPolicySigner : IDisposable
{
    public const string KeyId = "policy-check-key";

    private const string IssuedAt = "2026-11-11T00:00:00Z";

    private readonly ECDsa key = ECDsa.Create(ECCurve.NamedCurves.nistP256);

    public string PublicKeySpki => Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());

    public string Json(int version, string[] processes, string[] keywords, bool uploadEnabled,
        string? keyId = null, string? signatureOverride = null, string? extraField = null)
    {
        var effectiveKeyId = keyId ?? KeyId;
        var canonical = SigningCanonical.Policy(version, IssuedAt, processes, keywords, uploadEnabled);
        var signature = signatureOverride ?? Convert.ToBase64String(
            key.SignData(Encoding.UTF8.GetBytes(canonical), HashAlgorithmName.SHA256));
        var json = JsonSerializer.Serialize(new
        {
            policyVersion = version,
            issuedAt = IssuedAt,
            keyId = effectiveKeyId,
            processes,
            titleKeywords = keywords,
            uploadEnabled,
            signature,
        });
        return extraField is null ? json : json[..^1] + "," + extraField + "}";
    }

    public void Dispose() => key.Dispose();
}
