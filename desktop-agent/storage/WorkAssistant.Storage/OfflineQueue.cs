using System.Security.Cryptography;
using System.Text.Json;
using WorkAssistant.Core;

namespace WorkAssistant.Storage;

public sealed record QueuedSegment(SegmentDraft Draft, DateTimeOffset ExpiresAt);

public sealed record QueueRecoveryReport(int DeletedTemporaryFiles, int QuarantinedEntries, int KeptEntries);

/// <summary>
/// 离线队列：网络不可用时把已采集的元数据加密落盘，恢复后按到期时间清理并补传。
/// 每个条目单独加密，损坏条目只会被隔离，不会影响其他条目。
/// </summary>
public sealed class OfflineQueue
{
    private const string QueueDirectory = "queue";
    private const string QuarantineDirectory = "quarantine";

    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly DpapiVault vault;
    private readonly TimeSpan retention;
    private readonly Func<DateTimeOffset> clock;

    public OfflineQueue(DpapiVault vault, TimeSpan retention, Func<DateTimeOffset>? clock = null)
    {
        this.vault = vault;
        this.retention = retention;
        this.clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    public void Enqueue(SegmentDraft draft)
    {
        ArgumentNullException.ThrowIfNull(draft);
        var entry = new QueuedSegment(draft, clock() + retention);
        var payload = JsonSerializer.SerializeToUtf8Bytes(entry, Json);
        vault.Write(EntryPath(draft.ClientSegmentId), payload);
    }

    public int Count => Directory.Exists(QueuePath)
        ? Directory.GetFiles(QueuePath, "*.seg").Length
        : 0;

    public IReadOnlyList<QueuedSegment> ReadAll()
    {
        if (!Directory.Exists(QueuePath))
        {
            return Array.Empty<QueuedSegment>();
        }

        var entries = new List<QueuedSegment>();
        foreach (var file in Directory.GetFiles(QueuePath, "*.seg").OrderBy(name => name, StringComparer.Ordinal))
        {
            var plaintext = vault.Unprotect(File.ReadAllBytes(file));
            var entry = JsonSerializer.Deserialize<QueuedSegment>(plaintext, Json)
                ?? throw new InvalidDataException($"队列条目为空: {file}");
            entries.Add(entry);
        }

        return entries;
    }

    public void Remove(Guid clientSegmentId)
    {
        vault.Delete(EntryPath(clientSegmentId));
    }

    public void Clear()
    {
        if (Directory.Exists(QueuePath))
        {
            foreach (var file in Directory.GetFiles(QueuePath))
            {
                File.Delete(file);
            }
        }
    }

    /// <summary>删除到期条目，返回删除数量。</summary>
    public int PurgeExpired()
    {
        if (!Directory.Exists(QueuePath))
        {
            return 0;
        }

        var now = clock();
        var removed = 0;
        foreach (var file in Directory.GetFiles(QueuePath, "*.seg"))
        {
            try
            {
                var entry = JsonSerializer.Deserialize<QueuedSegment>(
                    vault.Unprotect(File.ReadAllBytes(file)), Json);
                if (entry is not null && entry.ExpiresAt <= now)
                {
                    File.Delete(file);
                    removed++;
                }
            }
            catch (Exception e) when (e is CryptographicException or JsonException or InvalidDataException)
            {
                // 损坏条目交给 Recover 隔离，这里不重复处理
            }
        }

        return removed;
    }

    /// <summary>
    /// 启动恢复：删除临时文件、隔离无法解密的条目、返回保留数量。
    /// 不改变采集状态；调用方必须把会话状态置回 Stopped。
    /// </summary>
    public QueueRecoveryReport Recover()
    {
        Directory.CreateDirectory(QueuePath);
        var deletedTemporary = 0;
        foreach (var temporary in Directory.GetFiles(QueuePath, "*.tmp"))
        {
            File.Delete(temporary);
            deletedTemporary++;
        }

        var quarantined = 0;
        var kept = 0;
        foreach (var file in Directory.GetFiles(QueuePath, "*.seg"))
        {
            try
            {
                var entry = JsonSerializer.Deserialize<QueuedSegment>(
                    vault.Unprotect(File.ReadAllBytes(file)), Json);
                if (entry is null)
                {
                    throw new InvalidDataException("空条目");
                }

                kept++;
            }
            catch (Exception e) when (e is CryptographicException or JsonException or InvalidDataException)
            {
                Quarantine(file);
                quarantined++;
            }
        }

        return new QueueRecoveryReport(deletedTemporary, quarantined, kept);
    }

    private void Quarantine(string file)
    {
        var directory = Path.Combine(vault.RootDirectory, QuarantineDirectory);
        Directory.CreateDirectory(directory);
        var name = Path.GetFileName(file);
        File.Move(file, Path.Combine(directory, $"{name}.{DateTime.UtcNow.Ticks}.bad"), overwrite: true);
    }

    private string QueuePath => Path.Combine(vault.RootDirectory, QueueDirectory);

    private static string EntryPath(Guid id) => $"{QueueDirectory}/{id:D}.seg";
}
