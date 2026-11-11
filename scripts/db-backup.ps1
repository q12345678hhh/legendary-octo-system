<#
.SYNOPSIS
    加密备份 + 对象存储清单 + 恢复基准（WP01-03）。

.DESCRIPTION
    产出三样东西，缺一不可：
    1. 加密备份文件：pg_dump 自定义格式 → AES-256-CBC（PBKDF2-HMAC-SHA256，20 万次）→ .dump.enc
    2. 对象存储清单：上传到 MinIO 的备份桶，并记录对象键、大小、密文与明文 SHA-256
    3. 恢复基准：租户数、outbox 行数、按 tenant_id 排序的聚合摘要，供恢复后逐项比对

    明文 dump 在容器内直接加密，宿主机上不存在未加密的数据库副本。
    口令来源：PLATFORM_BACKUP_PASSPHRASE，缺省读/写 var\backups\.passphrase（已在 .gitignore）。
    生产环境必须改为 KMS 或密钥服务托管，不能把口令放在环境变量或磁盘文件里。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\db-backup.ps1
#>
[CmdletBinding()]
param(
    [string]$Database = 'platform',
    [string]$BackupDir,
    [string]$Bucket = 'platform-backups',
    [string]$Tag
)

$ErrorActionPreference = 'Stop'
# wsl.exe 默认用 UTF-16 输出提示，管道里会混入 NUL 字节；强制 UTF-8 才能可靠过滤
$env:WSL_UTF8 = '1'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeDir = Join-Path $repoRoot 'deploy\compose'
if (-not $BackupDir) { $BackupDir = Join-Path $repoRoot 'var\backups' }
$pgContainer = if ($env:PLATFORM_PG_CONTAINER) { $env:PLATFORM_PG_CONTAINER } else { 'platform-pg-dev' }
$minioContainer = 'platform-minio-dev'
$mcImage = 'bitnamilegacy/minio-client:2025.7.21-debian-12-r3'
$composeNetwork = 'platform-dev_default'

function Read-EnvFile {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^#]*)') {
            $map[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $map
}

function ConvertTo-WslPath {
    param([string]$WindowsPath)
    # 目标文件可能尚不存在，不能用 Resolve-Path；直接按绝对路径换算
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
    # WSL 的 localhost 代理告警会混进 stdout，必须滤掉，否则会被当成数据
    $text = ((($out | ForEach-Object { "$_" }) | Where-Object { $_ -notmatch 'wsl:' }) -join "`n").Trim()
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "docker $($Arguments -join ' ') 失败（exit=$code）：`n$text"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Invoke-Psql {
    param([string]$Sql)
    $result = Invoke-Docker @('exec', '-e', "PGPASSWORD=$script:ownerPassword", $pgContainer,
        'psql', '-h', '127.0.0.1', '-p', '5432', '-U', 'platform_owner', '-d', $Database,
        '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c', $Sql)
    return $result.Output.Trim()
}

$envMap = Read-EnvFile (Join-Path $composeDir '.env')
$script:ownerPassword = $envMap['POSTGRES_OWNER_PASSWORD']
if (-not $script:ownerPassword) { throw '缺少 POSTGRES_OWNER_PASSWORD：先准备 deploy\compose\.env' }
$minioUser = $envMap['MINIO_ROOT_USER']
$minioPassword = $envMap['MINIO_ROOT_PASSWORD']
if (-not $minioUser -or -not $minioPassword) { throw '缺少 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD：先准备 deploy\compose\.env' }

# 口令：环境变量优先，其次本地密钥文件（仅限本地演练；生产必须用 KMS）
$passphrase = $env:PLATFORM_BACKUP_PASSPHRASE
$passphraseFile = Join-Path $BackupDir '.passphrase'
if (-not $passphrase) {
    if (Test-Path $passphraseFile) {
        $passphrase = (Get-Content -Raw $passphraseFile).Trim()
    } else {
        New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
        $bytes = New-Object byte[] 24
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $passphrase = [Convert]::ToBase64String($bytes)
        [System.IO.File]::WriteAllText($passphraseFile, $passphrase, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "已生成本地备份口令：$passphraseFile（不要提交、不要外发）"
    }
}

New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$script:docker = Resolve-Docker

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$suffix = if ($Tag) { "-$Tag" } else { '' }
$objectKey = "$Database-$stamp$suffix.dump.enc"
$localEnc = Join-Path $BackupDir $objectKey
$containerTmp = "/tmp/$objectKey"

Write-Host "备份 $Database → $objectKey"
# 一次 dump 同时产出明文与密文两个哈希：先落明文、立刻加密、立即删除明文
$dumpCmd = @"
set -eu
pg_dump -U platform_owner -d $Database -Fc -f /tmp/backup.plain
sha256sum /tmp/backup.plain | cut -d' ' -f1 > /tmp/backup.sha
openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_PASSPHRASE -in /tmp/backup.plain -out $containerTmp
rm -f /tmp/backup.plain
"@
Invoke-Docker @('exec', '-e', "BACKUP_PASSPHRASE=$passphrase", $pgContainer, 'bash', '-c', $dumpCmd) | Out-Null
$plainSha = (Invoke-Docker @('exec', $pgContainer, 'cat', '/tmp/backup.sha')).Output.Trim()

Invoke-Docker @('cp', "$($pgContainer):$containerTmp", $(if ($script:docker.Mode -eq 'wsl') { ConvertTo-WslPath $localEnc } else { $localEnc })) | Out-Null
Invoke-Docker @('exec', $pgContainer, 'rm', '-f', $containerTmp) | Out-Null

$cipherSha = (Get-FileHash -Algorithm SHA256 -Path $localEnc).Hash.ToLowerInvariant()
$sizeBytes = (Get-Item $localEnc).Length
Write-Host ("  密文 SHA-256：{0}（{1} 字节）" -f $cipherSha, $sizeBytes)
Write-Host ("  明文 SHA-256：{0}" -f $plainSha)

# 恢复基准：行数与聚合摘要；恢复后逐项比对，避免“恢复了但数据不对”
$tenants = Invoke-Psql 'SELECT count(*) FROM tenancy_tenant;'
$outbox = Invoke-Psql 'SELECT count(*) FROM platform_outbox;'
$tenantDigest = Invoke-Psql "SELECT md5(coalesce(string_agg(id::text || ':' || slug, ',' ORDER BY id), '')) FROM tenancy_tenant;"
$flywayVersion = Invoke-Psql 'SELECT coalesce(max(version), '''') FROM flyway_schema_history WHERE success;'

$manifest = [ordered]@{
    createdAt         = (Get-Date).ToUniversalTime().ToString('o')
    database          = $Database
    sourceContainer   = $pgContainer
    pgServerVersion   = (Invoke-Psql 'SHOW server_version;')
    flywayVersion     = $flywayVersion
    backupFile        = $objectKey
    encryption        = [ordered]@{
        algorithm  = 'AES-256-CBC'
        kdf        = 'PBKDF2-HMAC-SHA256'
        iterations = 200000
        salt       = 'openssl 随机盐，随密文头部保存'
    }
    files             = @(
        [ordered]@{
            path         = $objectKey
            objectKey    = "s3://$Bucket/$objectKey"
            sizeBytes    = $sizeBytes
            cipherSha256 = $cipherSha
            plainSha256  = $plainSha
        }
    )
    restoreBaseline   = [ordered]@{
        'tenancy_tenant.count'      = [int]$tenants
        'platform_outbox.count'     = [int]$outbox
        'tenancy_tenant.md5_ordered' = $tenantDigest
    }
}

# 上传并核对对象存储清单
Write-Host "上传到对象存储桶 $Bucket"
$mountPath = if ($script:docker.Mode -eq 'wsl') { ConvertTo-WslPath $BackupDir } else { (Resolve-Path $BackupDir).Path }
$mcScript = "set -eu; mc alias set local http://minio:9000 '$minioUser' '$minioPassword' >/dev/null; " +
        "mc mb --ignore-existing local/$Bucket >/dev/null; " +
        "mc cp /backups/$objectKey local/$Bucket/$objectKey >/dev/null; " +
        "mc stat --json local/$Bucket/$objectKey"
$mcResult = Invoke-Docker @('run', '--rm', '--entrypoint', 'sh', '--network', $composeNetwork,
    '-v', "${mountPath}:/backups", $mcImage, '-c', $mcScript)
Write-Host ($mcResult.Output -split "`r?`n" | Where-Object { $_.Trim() } | ForEach-Object { "  $_" })

$manifest['objectStorage'] = [ordered]@{
    bucket     = $Bucket
    objectKey  = $objectKey
    verifiedBy = ($mcResult.Output -split "`r?`n" | Where-Object { $_.Trim() })
}

$manifestPath = Join-Path $BackupDir "$Database-$stamp$suffix.manifest.json"
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $manifestPath
Write-Host ''
Write-Host "备份完成：$localEnc"
Write-Host "清单：$manifestPath"
Write-Host ("恢复基准：tenants={0} outbox={1} md5={2} flyway={3}" -f $tenants, $outbox, $tenantDigest, $flywayVersion)
