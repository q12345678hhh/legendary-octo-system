<#
.SYNOPSIS
    加密备份的隔离恢复演练（WP01-03）。

.DESCRIPTION
    拉起一个与开发栈完全隔离、不接网络的 PostgreSQL 容器，按手册恢复备份，
    逐项比对清单里的基准值。顺序刻意先失败后成功：

    1. 失败样本：用被截断的密文解密，必须失败并记录错误——证明损坏不会被静默接受。
    2. 修复核对：重新校验正式备份的 SHA-256 与清单一致（备份可用）。
    3. 复测：完整恢复到隔离实例，比对 flyway 版本、租户数、outbox 行数与聚合摘要。

    本阶段只做最小恢复演练，不冒充 GA 恢复结果：不含对象存储附件、密钥版本与审计链校验，
    这些按 NFR-08 在 WP11-05 完成。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\db-restore-drill.ps1
#>
[CmdletBinding()]
param(
    [string]$BackupDir,
    [string]$ManifestPath,
    [string]$ContainerName = 'platform-restore-drill',
    [switch]$KeepContainer
)

$ErrorActionPreference = 'Stop'
$env:WSL_UTF8 = '1'

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $BackupDir) { $BackupDir = Join-Path $repoRoot 'var\backups' }

function ConvertTo-WslPath {
    param([string]$WindowsPath)
    $full = [System.IO.Path]::GetFullPath($WindowsPath)
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $rest = $full.Substring(2).Replace('\', '/')
    return "/mnt/$drive$rest"
}

function Resolve-Docker {
    $ErrorActionPreference = 'Continue'
    $native = Get-Command docker -ErrorAction SilentlyContinue
    if ($native) {
        & docker info 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return [pscustomobject]@{ Mode = 'native'; Distro = $null } }
    }
    $distro = if ($env:PLATFORM_WSL_DISTRO) { $env:PLATFORM_WSL_DISTRO } else { 'pglab' }
    & wsl -d $distro -u root -- docker info 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { return [pscustomobject]@{ Mode = 'wsl'; Distro = $distro } }
    throw '找不到可用的 Docker：先启动 Docker Desktop，或 scripts\dev-up.ps1 拉起 WSL 内的 Engine'
}

function Invoke-Docker {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $ErrorActionPreference = 'Continue'
    if ($script:docker.Mode -eq 'native') {
        $out = & docker @Arguments 2>&1
    } else {
        $out = & wsl -d $script:docker.Distro -u root -- docker @Arguments 2>&1
    }
    $code = $LASTEXITCODE
    $text = ((($out | ForEach-Object { "$_" }) | Where-Object { $_ -notmatch 'wsl:' }) -join "`n").Trim()
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "docker $($Arguments -join ' ') 失败（exit=$code）：`n$text"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Invoke-RestorePsql {
    param([string]$Database, [string]$Sql)
    $result = Invoke-Docker @('exec', $ContainerName, 'psql', '-U', 'postgres', '-d', $Database, '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c', $Sql)
    return $result.Output.Trim()
}

function Assert-Equal {
    param([string]$Expected, [string]$Actual, [string]$What)
    if ("$Expected".Trim() -ne "$Actual".Trim()) {
        throw "校验失败：$What 期望 [$Expected]，实际 [$Actual]"
    }
    Write-Host ("  [通过] {0} = {1}" -f $What, $Actual)
}

$script:docker = Resolve-Docker

if (-not $ManifestPath) {
    $latest = Get-ChildItem -Path $BackupDir -Filter '*.manifest.json' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { throw "在 $BackupDir 找不到备份清单，先跑 scripts\db-backup.ps1" }
    $ManifestPath = $latest.FullName
}
$manifest = Get-Content -Raw -Encoding UTF8 $ManifestPath | ConvertFrom-Json
$backupPath = Join-Path $BackupDir $manifest.backupFile
if (-not (Test-Path $backupPath)) { throw "清单指向的备份文件不存在：$backupPath" }

$passphrase = $env:PLATFORM_BACKUP_PASSPHRASE
if (-not $passphrase) {
    $passFile = Join-Path $BackupDir '.passphrase'
    if (-not (Test-Path $passFile)) { throw "缺少备份口令：设置 PLATFORM_BACKUP_PASSPHRASE 或提供 $passFile" }
    $passphrase = (Get-Content -Raw $passFile).Trim()
}

Write-Host "清单：$ManifestPath"
Write-Host "备份：$($manifest.backupFile)（$($manifest.files[0].sizeBytes) 字节，$($manifest.createdAt)）"

$started = Get-Date

# ---------------------------------------------------------------- 1 失败样本
Write-Host ''
Write-Host '== 1 失败样本：损坏密文必须被拒绝'
$corruptPath = Join-Path $BackupDir ("corrupt-" + $manifest.backupFile)
$bytes = [System.IO.File]::ReadAllBytes($backupPath)
[System.IO.File]::WriteAllBytes($corruptPath, $bytes[0..([int]($bytes.Length / 2))])

Invoke-Docker @('rm', '-f', $ContainerName) -AllowFailure | Out-Null
Invoke-Docker @('run', '-d', '--name', $ContainerName, '--network', 'none',
    '-e', 'POSTGRES_PASSWORD=drill_only_not_production', 'postgres:16') | Out-Null
$corruptSource = if ($script:docker.Mode -eq 'wsl') { ConvertTo-WslPath $corruptPath } else { $corruptPath }
Invoke-Docker @('cp', $corruptSource, "${ContainerName}:/tmp/corrupt.dump.enc") | Out-Null

$corruptDecryptCmd = @'
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSPHRASE -in /tmp/corrupt.dump.enc -out /tmp/corrupt.dump
'@
$decryptFail = Invoke-Docker @('exec', '-e', "BACKUP_PASSPHRASE=$passphrase", $ContainerName, 'sh', '-c', $corruptDecryptCmd) -AllowFailure
if ($decryptFail.ExitCode -eq 0) {
    throw '损坏的密文竟然解密成功：加密或校验逻辑失效'
}
Write-Host "  解密按预期失败（exit=$($decryptFail.ExitCode)）"
$decryptError = (($decryptFail.Output -split "`r?`n") | Where-Object { $_.Trim() } | Select-Object -First 2) -join ' '
Write-Host "  $decryptError"
Remove-Item $corruptPath -Force

# ---------------------------------------------------------------- 2 修复核对
Write-Host ''
Write-Host '== 2 修复核对：正式备份的密文哈希必须与清单一致'
$actualCipher = (Get-FileHash -Algorithm SHA256 -Path $backupPath).Hash.ToLowerInvariant()
Assert-Equal $manifest.files[0].cipherSha256 $actualCipher 'cipherSha256'

# ---------------------------------------------------------------- 3 隔离恢复
Write-Host ''
Write-Host '== 3 隔离恢复：不接网络的独立实例'
if ($script:docker.Mode -eq 'wsl') {
    Invoke-Docker @('cp', (ConvertTo-WslPath $backupPath), "${ContainerName}:/tmp/backup.dump.enc") | Out-Null
} else {
    Invoke-Docker @('cp', $backupPath, "${ContainerName}:/tmp/backup.dump.enc") | Out-Null
}

$deadline = (Get-Date).AddSeconds(90)
do {
    $ready = Invoke-Docker @('exec', $ContainerName, 'pg_isready', '-U', 'postgres') -AllowFailure
    if ($ready.ExitCode -eq 0) { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if ((Get-Date) -ge $deadline) { throw '隔离实例 90 秒内未就绪' }

$restoreCmd = @'
set -eu
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSPHRASE -in /tmp/backup.dump.enc -out /tmp/backup.dump
test -s /tmp/backup.dump
'@
Invoke-Docker @('exec', '-e', "BACKUP_PASSPHRASE=$passphrase", $ContainerName, 'sh', '-c', $restoreCmd) | Out-Null
Invoke-Docker @('exec', $ContainerName, 'createdb', '-U', 'postgres', 'platform') | Out-Null

$restoreStart = Get-Date
Invoke-Docker @('exec', $ContainerName, 'pg_restore', '-U', 'postgres', '-d', 'platform',
    '--no-owner', '--no-privileges', '--exit-on-error', '/tmp/backup.dump') | Out-Null
$restoreSeconds = [math]::Round(((Get-Date) - $restoreStart).TotalSeconds, 1)
Write-Host "  pg_restore 完成，耗时 $restoreSeconds s"

Write-Host ''
Write-Host '== 4 一致性校验'
$flyway = Invoke-RestorePsql 'platform' "SELECT coalesce(max(version), '') FROM flyway_schema_history WHERE success;"
$tenants = Invoke-RestorePsql 'platform' 'SELECT count(*) FROM tenancy_tenant;'
$outbox = Invoke-RestorePsql 'platform' 'SELECT count(*) FROM platform_outbox;'
$digest = Invoke-RestorePsql 'platform' "SELECT md5(coalesce(string_agg(id::text || ':' || slug, ',' ORDER BY id), '')) FROM tenancy_tenant;"

Assert-Equal $manifest.flywayVersion $flyway 'flyway 最大版本'
Assert-Equal $manifest.restoreBaseline.'tenancy_tenant.count' $tenants 'tenancy_tenant 行数'
Assert-Equal $manifest.restoreBaseline.'platform_outbox.count' $outbox 'platform_outbox 行数'
Assert-Equal $manifest.restoreBaseline.'tenancy_tenant.md5_ordered' $digest 'tenancy_tenant 聚合摘要'

$elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
Write-Host ''
Write-Host "恢复演练通过：从损坏样本被拒到完整校验通过共 $elapsed s（隔离实例，不含生产网络与附件恢复）"

if (-not $KeepContainer) {
    Invoke-Docker @('rm', '-f', $ContainerName) | Out-Null
    Write-Host "已删除隔离实例 $ContainerName"
}
