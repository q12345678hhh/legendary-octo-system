using System.Text.Json;
using System.Text.Json.Serialization;
using WorkAssistant.PrivacyEngine;

namespace WorkAssistant.Core;

/// <summary>服务端下发的策略信封。字段集合封闭：未知字段一律拒收。</summary>
public sealed record PolicyEnvelope(
    [property: JsonPropertyName("policyVersion")] int PolicyVersion,
    [property: JsonPropertyName("issuedAt")] string IssuedAt,
    [property: JsonPropertyName("keyId")] string KeyId,
    [property: JsonPropertyName("processes")] IReadOnlyList<string> Processes,
    [property: JsonPropertyName("titleKeywords")] IReadOnlyList<string> TitleKeywords,
    [property: JsonPropertyName("uploadEnabled")] bool UploadEnabled,
    [property: JsonPropertyName("signature")] string Signature);

/// <summary>验签并合并本地硬编码下限后的有效策略。</summary>
public sealed record PolicySnapshot(
    int PolicyVersion,
    string IssuedAt,
    string KeyId,
    IReadOnlySet<string> Processes,
    IReadOnlyList<string> TitleKeywords,
    bool UploadEnabled,
    string Signature);

/// <summary>策略验签与合并。</summary>
public static class PolicyVerifier
{
    private static readonly JsonSerializerOptions StrictJson = new()
    {
        // 拒绝任何未知字段：策略里不可能藏第二个远程控制通道
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        PropertyNameCaseInsensitive = false,
    };

    /// <summary>
    /// 解析、验签、校验 key id 与版本单调，最后与本地硬编码下限取并集。
    /// 任一环节失败都抛异常，调用方必须保持原有策略（失败即拒绝）。
    /// </summary>
    public static PolicySnapshot Verify(string policyJson, string pinnedPublicKeySpki,
        string pinnedKeyId, int lastAppliedVersion)
    {
        var envelope = JsonSerializer.Deserialize<PolicyEnvelope>(policyJson, StrictJson)
            ?? throw new InvalidOperationException("策略为空");

        if (!string.Equals(envelope.KeyId, pinnedKeyId, StringComparison.Ordinal))
        {
            throw new InvalidOperationException($"策略 key id 不匹配: {envelope.KeyId}");
        }

        if (envelope.PolicyVersion <= lastAppliedVersion)
        {
            throw new InvalidOperationException(
                $"策略版本 {envelope.PolicyVersion} 不大于已应用版本 {lastAppliedVersion}，拒绝回滚");
        }

        var canonical = SigningCanonical.Policy(envelope.PolicyVersion, envelope.IssuedAt,
            envelope.Processes, envelope.TitleKeywords, envelope.UploadEnabled);
        if (!VerifySignature(pinnedPublicKeySpki, canonical, envelope.Signature))
        {
            throw new InvalidOperationException("策略签名无效");
        }

        var processes = new HashSet<string>(ForbiddenApplicationPolicy.DefaultForbiddenProcesses,
            StringComparer.OrdinalIgnoreCase);
        processes.UnionWith(envelope.Processes);

        var keywords = new List<string>(ForbiddenApplicationPolicy.DefaultForbiddenTitleKeywords);
        foreach (var keyword in envelope.TitleKeywords)
        {
            if (!keywords.Contains(keyword, StringComparer.OrdinalIgnoreCase))
            {
                keywords.Add(keyword);
            }
        }

        return new PolicySnapshot(envelope.PolicyVersion, envelope.IssuedAt, envelope.KeyId,
            processes, keywords, envelope.UploadEnabled, envelope.Signature);
    }

    public static bool VerifySignature(string publicKeySpki, string canonical, string signatureBase64)
    {
        try
        {
            using var key = System.Security.Cryptography.ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(publicKeySpki), out _);
            return key.VerifyData(
                System.Text.Encoding.UTF8.GetBytes(canonical),
                Convert.FromBase64String(signatureBase64),
                System.Security.Cryptography.HashAlgorithmName.SHA256);
        }
        catch (Exception e) when (e is FormatException or System.Security.Cryptography.CryptographicException)
        {
            return false;
        }
    }
}
