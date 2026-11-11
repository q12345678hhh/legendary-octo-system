using System.Globalization;
using System.IO.Compression;
using WorkAssistant.Device;

namespace WorkAssistant.Adapters;

/// <summary>
/// 生产版更新安装器：ZIP 载荷覆盖安装到指定目录。
///
/// <p>安装前把当前目录完整备份；任一步失败时 <see cref="RestorePrevious"/> 恢复旧文件并删除本次新增文件，
/// 不让客户端停在半安装状态。ZIP 条目必须落在目标目录内，目录穿越一律拒绝。
/// </summary>
public sealed class ZipPackageInstaller : IUpdateInstaller
{
    private readonly string installDirectory;
    private readonly string backupDirectory;
    private readonly List<string> addedFiles = new();
    private string? lastBackup;

    public ZipPackageInstaller(string installDirectory, string backupDirectory)
    {
        this.installDirectory = Path.GetFullPath(installDirectory);
        this.backupDirectory = Path.GetFullPath(backupDirectory);
        Directory.CreateDirectory(this.installDirectory);
        Directory.CreateDirectory(this.backupDirectory);
    }

    public string InstallDirectory => installDirectory;

    public void Install(string packagePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(packagePath);
        CleanupOldBackups();

        var backup = Path.Combine(backupDirectory,
            "backup-" + DateTimeOffset.UtcNow.ToString("yyyyMMddHHmmssfff", CultureInfo.InvariantCulture));
        CopyDirectory(installDirectory, backup);
        lastBackup = backup;
        addedFiles.Clear();

        var staging = Path.Combine(backupDirectory, "staging-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(staging);
        try
        {
            Extract(packagePath, staging);
            foreach (var source in Directory.GetFiles(staging, "*", SearchOption.AllDirectories))
            {
                var target = Path.GetFullPath(Path.Combine(installDirectory, Path.GetRelativePath(staging, source)));
                EnsureInside(installDirectory, target);
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                if (!File.Exists(target))
                {
                    addedFiles.Add(target);
                }

                File.Copy(source, target, overwrite: true);
            }
        }
        finally
        {
            Delete(staging);
        }
    }

    public void RestorePrevious()
    {
        if (lastBackup is null || !Directory.Exists(lastBackup))
        {
            return;
        }

        // 先删本次新增文件，再用备份覆盖，避免旧版本旁边残留新版本文件。
        foreach (var file in addedFiles)
        {
            if (File.Exists(file))
            {
                File.Delete(file);
            }
        }

        addedFiles.Clear();
        CopyDirectory(lastBackup, installDirectory);
        Delete(lastBackup);
        lastBackup = null;
    }

    private static void Extract(string packagePath, string staging)
    {
        using var archive = ZipFile.OpenRead(packagePath);
        foreach (var entry in archive.Entries)
        {
            if (string.IsNullOrEmpty(entry.Name))
            {
                continue;
            }

            var destination = Path.GetFullPath(Path.Combine(staging, entry.FullName));
            EnsureInside(staging, destination);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            entry.ExtractToFile(destination, overwrite: true);
        }
    }

    private static void EnsureInside(string root, string path)
    {
        var prefix = root.EndsWith(Path.DirectorySeparatorChar)
            ? root
            : root + Path.DirectorySeparatorChar;
        if (!path.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException($"更新包条目越出安装目录: {path}");
        }
    }

    private static void CopyDirectory(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (var directory in Directory.GetDirectories(source, "*", SearchOption.AllDirectories))
        {
            Directory.CreateDirectory(Path.Combine(destination, Path.GetRelativePath(source, directory)));
        }

        foreach (var file in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
        {
            File.Copy(file, Path.Combine(destination, Path.GetRelativePath(source, file)), overwrite: true);
        }
    }

    /// <summary>只保留最近一次备份，避免升级反复重试时无限占用磁盘。</summary>
    private void CleanupOldBackups()
    {
        foreach (var directory in Directory.GetDirectories(backupDirectory, "backup-*"))
        {
            Delete(directory);
        }
    }

    private static void Delete(string directory)
    {
        if (Directory.Exists(directory))
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
