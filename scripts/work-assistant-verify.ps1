<#
.SYNOPSIS
    WP01-05 工作助手技术骨架的真实链路取证。

.DESCRIPTION
    在本机 API + WSL 内 PostgreSQL 上逐项验证并归档证据：

    1. 桌面端 Release 构建零警告；离线自检覆盖采集门禁、托盘提示、DPAPI、
       离线队列、崩溃恢复、策略验签与更新回滚。
    2. 托盘壳 --self-check：每个状态的菜单与置顶提示可见性都来自同一展示层，
       任何状态都没有隐藏或静默入口。
    3. V5 迁移在真实库上应用，三张新表均为 RLS + FORCE 且各有一条策略。
    4. 设备注册、签名策略、版本心跳（含最低版本门禁）。
    5. 策略只能追加排除项：未知字段与删除列表一律 422；没有远程开始采集端点。
    6. 员工未开始或已暂停时采集量为 0；撤销设备后上传被拒且本地队列清空。
    7. 崩溃恢复：上传成功但未删本地条目时，重启后重传由 client_segment_id 去重。

    产物写入 docs/evidence/wp01-05/，任一步失败即抛错退出。
    私钥只生成在 var/wp01-05/ 下，不进入版本库。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\work-assistant-verify.ps1
#>
[CmdletBinding()]
param(
    [int]$ApiPort = 18081,
    [string]$EvidenceDir,
    [switch]$SkipBackendBuild,
    [switch]$SkipDesktopBuild,
    [switch]$KeepProcesses
)

$ErrorActionPreference = 'Stop'
$env:WSL_UTF8 = '1'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
$evidence = if ($EvidenceDir) { $EvidenceDir } else { Join-Path $repoRoot 'docs\evidence\wp01-05' }
$work = Join-Path $env:TEMP ("wp01-05-verify-" + [guid]::NewGuid().ToString('N'))
$envMap = @{}
$baseUrl = "http://127.0.0.1:$ApiPort"
$distro = if ($env:PLATFORM_WSL_DISTRO) { $env:PLATFORM_WSL_DISTRO } else { 'pglab' }
$pgContainer = if ($env:PLATFORM_PG_CONTAINER) { $env:PLATFORM_PG_CONTAINER } else { 'platform-pg-dev' }
$tenantA = 'aaaaaaaa-0000-0000-0000-000000000001'
$subject1 = '11111111-1111-4111-8111-111111111111'
$desktop = Join-Path $repoRoot 'desktop-agent'
$checksExe = Join-Path $desktop 'device\WorkAssistant.Device.Checks\bin\Release\net8.0-windows\WorkAssistant.Device.Checks.exe'
$appExe = Join-Path $desktop 'windows-app\WorkAssistant.WindowsApp\bin\Release\net8.0-windows\WorkAssistant.WindowsApp.exe'
$keyDir = Join-Path $repoRoot 'var\wp01-05\keys'
$deviceWork = Join-Path $work 'device'
$apiProcess = $null

New-Item -ItemType Directory -Force -Path $evidence | Out-Null
New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path $deviceWork | Out-Null
# 只清理本脚本自己的产物，避免误删同目录下 db-verify.txt 等其他脚本的证据
$ownedEvidence = @(
    'desktop-build.txt', 'offline-checks.txt', 'capture-gating.txt', 'indicator.txt',
    'secure-storage.txt', 'offline-queue.txt', 'crash-recovery.txt', 'update-rollback.txt',
    'db-migration.txt', 'device-registration.txt', 'policy-signature.txt', 'device-revocation.txt'
)
foreach ($name in $ownedEvidence) {
    $path = Join-Path $evidence $name
    if (Test-Path $path) { Remove-Item $path -Force }
}

function Write-Step {
    param([string]$Text)
    Write-Host ''
    Write-Host "== $Text"
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "断言失败：$Message" }
}

function Write-TextFile {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Add-Evidence {
    param([string]$Name, [string]$Text)
    Add-Content -Path (Join-Path $evidence $Name) -Value $Text -Encoding UTF8
}

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
    param([string]$Path)
    $drive = $Path.Substring(0, 1).ToLower()
    return "/mnt/$drive" + ($Path.Substring(2) -replace '\\', '/')
}

function Invoke-Wsl {
    param([string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    $out = & wsl -d $distro -u root -- @Arguments 2>&1
    $code = $LASTEXITCODE
    $text = (($out | ForEach-Object { "$_" }) | Where-Object { $_ -notmatch '^wsl:' }) -join "`n"
    if ($code -ne 0) { throw "WSL 命令失败（exit=$code）：$text" }
    return $text
}

function Invoke-Psql {
    param([string]$Sql, [string]$Database = 'platform', [string]$User = 'platform_owner',
          [string]$Password = $envMap['POSTGRES_OWNER_PASSWORD'], [switch]$AllowFailure)
    $ErrorActionPreference = 'Continue'
    $out = $Sql | & wsl -d $distro -u root -- docker exec -i `
        -e "PGPASSWORD=$Password" -e 'PGCLIENTENCODING=UTF8' $pgContainer `
        psql -h 127.0.0.1 -p 5432 -U $User -d $Database -v ON_ERROR_STOP=1 -t -A 2>&1
    $code = $LASTEXITCODE
    $text = (($out | ForEach-Object { "$_" }) | Where-Object { $_ -notmatch '^wsl:' }) -join "`n"
    if ($code -ne 0 -and -not $AllowFailure) { throw "psql 失败（exit=$code）：$text" }
    return $text
}

# psql -t -A 会把同会话的 SET/DELETE 也打出来，断言只取最后一条查询结果
function Select-PsqlValue {
    param([string]$Text)
    $values = @($Text -split "`n" | Where-Object { $_ -and $_ -notmatch '^(SET|DELETE|INSERT|UPDATE)$' })
    return $values[-1].Trim()
}

function Invoke-Api {
    param(
        [string]$Method = 'GET',
        [string]$Path,
        [hashtable]$Headers = @{},
        [string]$Json,
        [string]$BodyFile
    )

    $stamp = [guid]::NewGuid().ToString('N')
    $payload = Join-Path $work "req-$stamp.bin"
    $response = Join-Path $work "resp-$stamp.txt"
    $headerFile = Join-Path $work "hdr-$stamp.txt"

    if ($Json) { Write-TextFile $payload $Json }
    elseif ($BodyFile) { $payload = $BodyFile }

    $curlArgs = @('-s', '-X', $Method, '-D', $headerFile, '-o', $response, '-w', '%{http_code}')
    foreach ($key in $Headers.Keys) {
        $curlArgs += @('-H', ("{0}: {1}" -f $key, $Headers[$key]))
    }
    if (($Json) -or ($BodyFile)) {
        $curlArgs += @('-H', 'Content-Type: application/json')
        $curlArgs += @('--data-binary', "@$payload")
    }
    $curlArgs += ($baseUrl + $Path)

    $status = (& curl.exe @curlArgs) -join ''
    $headerText = if (Test-Path $headerFile) { Get-Content -Raw -Encoding UTF8 $headerFile } else { '' }
    $body = if (Test-Path $response) { Get-Content -Raw -Encoding UTF8 $response } else { '' }
    $parsed = $null
    try { if ($body) { $parsed = $body | ConvertFrom-Json } } catch { }

    return [pscustomobject]@{
        Status = [int]$status
        Headers = $headerText
        Body = $body
        Json = $parsed
    }
}

function New-ApiHeaders {
    param([string]$Tenant = $tenantA, [string]$Subject = $subject1)
    return @{
        'X-Tenant-Id' = $Tenant
        'X-Subject-Id' = $Subject
        'Accept' = 'application/json, application/problem+json'
        'X-Correlation-Id' = 'cor-' + [guid]::NewGuid().ToString('N')
    }
}

function Wait-Until {
    param([scriptblock]$Probe, [int]$TimeoutSeconds = 60, [int]$IntervalMillis = 500, [string]$What)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $result = & $Probe
        if ($result) { return $result }
        Start-Sleep -Milliseconds $IntervalMillis
    }
    throw "等待超时：$What"
}

function Start-PlatformProcess {
    param([string]$Jar, [string[]]$Arguments, [string]$LogName)
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $outLog = Join-Path $work "$LogName.out.log"
    $errLog = Join-Path $work "$LogName.err.log"
    $all = @('-jar', $Jar) + $Arguments
    $process = Start-Process -FilePath $java -ArgumentList $all -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    return [pscustomobject]@{ Process = $process; OutLog = $outLog; ErrLog = $errLog }
}

function Invoke-DeviceChecks {
    param([string]$Phase)
    $arguments = @('--live', '--api', $baseUrl, '--tenant', $tenantA, '--subject', $subject1,
        '--workdir', $deviceWork, '--phase', $Phase)
    $text = & $checksExe @arguments 2>&1 | Out-String
    $code = $LASTEXITCODE
    Assert-True ($code -eq 0) "设备链路阶段 $Phase 失败（exit=$code）：`n$text"
    return $text
}

try {
    Write-Step '准备：环境变量、Docker、JDK 与密钥'
    $envMap = Read-EnvFile (Join-Path $repoRoot 'deploy\compose\.env')
    Assert-True ($envMap.ContainsKey('POSTGRES_APP_PASSWORD')) 'deploy/compose/.env 缺少 POSTGRES_APP_PASSWORD'

    $pinnedJavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
    if (Test-Path $pinnedJavaHome) { $env:JAVA_HOME = $pinnedJavaHome }
    Assert-True (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')) '找不到 JDK 21'

    try {
        Invoke-Wsl @('docker', 'info', '--format', '{{.ServerVersion}}') | Out-Null
    } catch {
        Invoke-Wsl @('sh', (ConvertTo-WslPath (Join-Path $repoRoot 'deploy\wsl\dockup.sh')), 'start') | Out-Null
        Invoke-Wsl @('docker', 'info', '--format', '{{.ServerVersion}}') | Out-Null
    }
    Invoke-Wsl @('docker', 'inspect', '-f', '{{.State.Running}}', $pgContainer) | Out-Null
    $dbHost = ((Invoke-Wsl @('hostname', '-I')) -split '\s+' | Where-Object { $_ } | Select-Object -First 1)
    Assert-True ($null -ne $dbHost) '无法确定 WSL IP'
    Write-Host "数据库地址：$dbHost"

    Write-Step '桌面端：Release 构建、离线自检与托盘壳自检'
    if (-not $SkipDesktopBuild) {
        Push-Location $desktop
        try {
            $build = & dotnet build desktop-agent.sln -c Release -v m 2>&1 | Out-String
            $buildCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        Assert-True ($buildCode -eq 0) "桌面端构建失败：`n$build"
        Assert-True (-not ($build -match '\bwarning (CS|CA|NETSDK|MSB)\d+')) "桌面端构建出现警告：`n$build"
        Write-TextFile (Join-Path $evidence 'desktop-build.txt') "dotnet build desktop-agent.sln -c Release`n退出码=0，无警告`n`n$build"
    }

    Assert-True (Test-Path $checksExe) "缺少自检产物：$checksExe"
    $offline = & $checksExe 2>&1 | Out-String
    Assert-True ($LASTEXITCODE -eq 0) "设备离线自检失败：`n$offline"
    Write-TextFile (Join-Path $evidence 'offline-checks.txt') $offline
    $offlineLines = @($offline -split "`n")

    $gating = @($offlineLines | Where-Object { $_ -match '采集|提示|托盘|私人模式' })
    Write-TextFile (Join-Path $evidence 'capture-gating.txt') (
        (@('# 来源：WorkAssistant.Device.Checks 离线自检（采集门禁与托盘提示）') + $gating) -join "`n")
    $storage = @($offlineLines | Where-Object { $_ -match 'DPAPI|状态|原子|路径' })
    Write-TextFile (Join-Path $evidence 'secure-storage.txt') (
        (@('# 来源：WorkAssistant.Device.Checks 离线自检（DPAPI 与本地状态）') + $storage) -join "`n")
    $queue = @($offlineLines | Where-Object { $_ -match '离线|队列|隔离|恢复' })
    Write-TextFile (Join-Path $evidence 'offline-queue.txt') (
        (@('# 来源：WorkAssistant.Device.Checks 离线自检（加密队列与恢复）') + $queue) -join "`n")
    $update = @($offlineLines | Where-Object { $_ -match '更新|回滚|降级' })
    Write-TextFile (Join-Path $evidence 'update-rollback.txt') (
        (@('# 来源：WorkAssistant.Device.Checks 离线自检（更新验签与回滚）') + $update) -join "`n")

    Assert-True (Test-Path $appExe) "缺少托盘壳产物：$appExe"
    $selfReport = Join-Path $work 'self-check.txt'
    $self = Start-Process -FilePath $appExe -ArgumentList @('--self-check', '--report', "`"$selfReport`"") `
        -Wait -PassThru
    Assert-True ($self.ExitCode -eq 0) "托盘壳自检失败（exit=$($self.ExitCode)）"
    $selfText = Get-Content -Raw -Encoding UTF8 $selfReport
    Write-TextFile (Join-Path $evidence 'indicator.txt') $selfText

    New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
    & $checksExe --generate-keys $keyDir | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) '生成取证用签名密钥失败'
    $policyKey = (Get-Content -Raw -Encoding UTF8 (Join-Path $keyDir 'policy.key')).Trim()
    $policyPublic = (Get-Content -Raw -Encoding UTF8 (Join-Path $keyDir 'policy.pub')).Trim()
    $updateKey = (Get-Content -Raw -Encoding UTF8 (Join-Path $keyDir 'update.key')).Trim()
    $updatePublic = (Get-Content -Raw -Encoding UTF8 (Join-Path $keyDir 'update.pub')).Trim()

    Write-Step '构建后端（包含 V5 迁移与工作助手端点）'
    if (-not $SkipBackendBuild) {
        $mvn = Get-Command mvn -ErrorAction SilentlyContinue
        Assert-True ($null -ne $mvn) '找不到 mvn'
        Push-Location (Join-Path $repoRoot 'backend')
        try {
            $mavenLog = & mvn -B -q -pl boot-api -am package -DskipTests 2>&1 | Out-String
            $mavenCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        Assert-True ($mavenCode -eq 0) "后端构建失败：`n$mavenLog"
    }
    $apiJar = Join-Path $repoRoot 'backend\boot-api\target\boot-api-0.1.0-SNAPSHOT.jar'
    Assert-True (Test-Path $apiJar) "缺少构建产物：$apiJar"

    Write-Step '启动 API（本机进程，连接 WSL 内依赖）'
    $env:PLATFORM_DB_URL = "jdbc:postgresql://${dbHost}:5432/platform"
    $env:PLATFORM_DB_USER = 'platform_app'
    $env:PLATFORM_DB_PASSWORD = $envMap['POSTGRES_APP_PASSWORD']
    $env:PLATFORM_DB_MIGRATION_USER = 'platform_owner'
    $env:PLATFORM_DB_MIGRATION_PASSWORD = $envMap['POSTGRES_OWNER_PASSWORD']
    $env:PLATFORM_REDIS_HOST = $dbHost
    $env:PLATFORM_STORAGE_ENDPOINT = "http://${dbHost}:9000"
    $env:PLATFORM_STORAGE_HEALTH_URL = "http://${dbHost}:9000/minio/health/live"
    $env:PLATFORM_STORAGE_ACCESS_KEY = $envMap['MINIO_ROOT_USER']
    $env:PLATFORM_STORAGE_SECRET_KEY = $envMap['MINIO_ROOT_PASSWORD']
    $env:PLATFORM_STORAGE_BUCKET = $envMap['MINIO_BUCKET']
    $env:PLATFORM_SMOKE_ENABLED = 'false'
    $env:PLATFORM_WA_POLICY_KEY = $policyKey
    $env:PLATFORM_WA_POLICY_PUBLIC_KEY = $policyPublic
    $env:PLATFORM_WA_POLICY_KEY_ID = 'local-policy-1'
    $env:PLATFORM_WA_UPDATE_KEY = $updateKey
    $env:PLATFORM_WA_UPDATE_PUBLIC_KEY = $updatePublic
    $env:PLATFORM_WA_UPDATE_KEY_ID = 'local-update-1'
    $env:PLATFORM_WA_MIN_VERSION = '0.1.0'
    $env:PLATFORM_WA_UPDATE_VERSION = '0.2.0'
    $env:PLATFORM_WA_UPDATE_URL = 'https://updates.example/work-assistant/0.2.0.zip'
    $env:PLATFORM_WA_UPDATE_SHA256 = ('a' * 64)

    $api = Start-PlatformProcess -Jar $apiJar -LogName 'api' -Arguments @("--server.port=$ApiPort")
    $apiProcess = $api.Process
    Wait-Until -What 'API 存活探针' -TimeoutSeconds 150 -Probe {
        try {
            $probe = Invoke-Api -Path '/actuator/health/liveness'
            return ($probe.Status -eq 200)
        } catch { return $false }
    } | Out-Null
    Write-Host "API 已启动 pid=$($apiProcess.Id)"

    Write-Step '数据库：V5 迁移、RLS 与租户种子'
    $migrations = Invoke-Psql 'select version from flyway_schema_history order by installed_rank'
    Write-TextFile (Join-Path $evidence 'db-migration.txt') ("flyway 版本序列：`n$migrations")
    Assert-True ($migrations -match '(?m)^5$') 'V5 未在真实库上应用'

    $rls = Invoke-Psql @"
select c.relname || ' rows=' || (select count(*) from pg_policy p where p.polrelid = c.oid) ||
       ' rls=' || c.relrowsecurity || ' force=' || c.relforcerowsecurity
from pg_class c
where c.relname in ('work_assistant_device','work_assistant_policy','work_assistant_segment')
order by c.relname;
"@
    Add-Evidence 'db-migration.txt' ("`n新表 RLS 状态：`n$rls")
    Assert-True ((@($rls -split "`n" | Where-Object { $_ -match 'rows=1 rls=true force=true' })).Count -eq 3) `
        '三张新表必须各有 1 条策略且 RLS+FORCE 生效'

    Invoke-Psql "SET app.tenant_id = '$tenantA'; INSERT INTO tenancy_tenant (id, slug, name, status) VALUES ('$tenantA','tenant-a','租户 A','active') ON CONFLICT (id) DO NOTHING;" | Out-Null
    # 每次取证从干净设备状态开始：上一次运行末尾会撤销设备
    Invoke-Psql "SET app.tenant_id = '$tenantA'; DELETE FROM work_assistant_segment WHERE tenant_id = '$tenantA'; DELETE FROM work_assistant_device WHERE tenant_id = '$tenantA'; DELETE FROM work_assistant_policy WHERE tenant_id = '$tenantA';" | Out-Null

    Write-Step '设备注册、签名策略与版本心跳'
    $enroll = Invoke-DeviceChecks -Phase 'enroll'
    Write-TextFile (Join-Path $evidence 'device-registration.txt') $enroll
    $deviceId = ([regex]::Match($enroll, 'DEVICE ([0-9a-fA-F-]{36})')).Groups[1].Value
    Assert-True ($deviceId -ne '') '未从注册输出中解析到设备 ID'

    Write-Step '策略只能追加排除项，且没有远程开启采集入口'
    $append = Invoke-Api -Method PUT -Path '/api/v1/work-assistant/policies/current' `
        -Headers (New-ApiHeaders) -Json '{"additional_exclusions":["Calculator"]}'
    Assert-True ($append.Status -eq 200) "追加排除项失败：$($append.Status) $($append.Body)"
    Assert-True ($append.Json.policyVersion -eq 2) "追加后策略版本应为 2，实际 $($append.Json.policyVersion)"
    Assert-True (($append.Json.processes -contains 'WeChat') -and ($append.Json.processes -contains 'Calculator')) `
        '追加后策略必须同时包含基线与新增排除项'

    $unknown = Invoke-Api -Method PUT -Path '/api/v1/work-assistant/policies/current' `
        -Headers (New-ApiHeaders) -Json '{"additional_exclusions":["Calculator"],"capture_enabled":true}'
    Assert-True ($unknown.Status -eq 422) "未知策略字段应 422，实际 $($unknown.Status)"
    Assert-True ($unknown.Json.code -eq 'POLICY_UNKNOWN_FIELD') "错误码应为 POLICY_UNKNOWN_FIELD，实际 $($unknown.Json.code)"
    $removal = Invoke-Api -Method PUT -Path '/api/v1/work-assistant/policies/current' `
        -Headers (New-ApiHeaders) -Json '{"processes":[]}'
    Assert-True ($removal.Status -eq 422) "整体覆盖策略应 422，实际 $($removal.Status)"
    $unsignedPolicy = Invoke-Api -Path "/api/v1/work-assistant/devices/$deviceId/policy" -Headers (New-ApiHeaders)
    Assert-True ($unsignedPolicy.Status -eq 400) "无设备签名的策略拉取应 400，实际 $($unsignedPolicy.Status)"
    $unsignedUpload = Invoke-Api -Method POST -Path '/api/v1/work-assistant/segments' `
        -Headers (New-ApiHeaders) -Json "{\"deviceId\":\"$deviceId\"}"
    Assert-True ($unsignedUpload.Status -eq 400) "无设备签名的上传应 400，实际 $($unsignedUpload.Status)"
    $remoteStart = Invoke-Api -Method POST -Path '/api/v1/work-assistant/capture' -Headers (New-ApiHeaders)
    Assert-True ($remoteStart.Status -eq 404) "不存在远程开始采集端点，应 404，实际 $($remoteStart.Status)"

    $policyEvidence = @(
        '# 客户端离线验签（来源：Device.Checks 离线自检）',
        (@($offlineLines | Where-Object { $_ -match '策略|验签|key id|回滚|未知字段' }) -join "`n"),
        '',
        '# 服务端策略边界（真实 API）',
        "追加排除项：status=$($append.Status) policyVersion=$($append.Json.policyVersion) processes=$($append.Json.processes -join ',')",
        "未知字段 capture_enabled：status=$($unknown.Status) code=$($unknown.Json.code)",
        "整体覆盖 processes：status=$($removal.Status) code=$($removal.Json.code)",
        "无签名策略拉取：status=$($unsignedPolicy.Status)",
        "无签名上传：status=$($unsignedUpload.Status)",
        "远程开始采集端点：status=$($remoteStart.Status)"
    )
    Write-TextFile (Join-Path $evidence 'policy-signature.txt') ($policyEvidence -join "`n")

    Write-Step '崩溃恢复：上传成功但未删本地条目'
    $crash = Invoke-DeviceChecks -Phase 'crash'
    $recover = Invoke-DeviceChecks -Phase 'recover'
    Add-Evidence 'crash-recovery.txt' (@(
            '# 崩溃前（上传成功但不删除本地条目）',
            $crash,
            '# 重启后（保留条目 → 重传 → 服务端判定重复）',
            $recover
        ) -join "`n")

    Write-Step '撤销设备：上传被拒并清空本地队列'
    $revoke = Invoke-DeviceChecks -Phase 'revoke'
    Write-TextFile (Join-Path $evidence 'device-revocation.txt') $revoke

    $segmentCount = (Invoke-Psql "SET app.tenant_id = '$tenantA'; SELECT count(*) FROM work_assistant_segment WHERE tenant_id = '$tenantA';") -split "`n" |
        Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1
    Assert-True ([int]$segmentCount -eq 3) "撤销后应只有 3 条已确认片段（崩溃重传不得重复），实际 $segmentCount"
    $deviceRow = Select-PsqlValue (Invoke-Psql "SET app.tenant_id = '$tenantA'; SELECT status || '|' || (revoked_at IS NOT NULL) || '|' || coalesce(revoked_by,'') FROM work_assistant_device WHERE tenant_id = '$tenantA';")
    Assert-True ($deviceRow -eq "revoked|true|$subject1") "设备状态应为 revoked|true|$subject1，实际 $deviceRow"
    $policyRow = Select-PsqlValue (Invoke-Psql "SET app.tenant_id = '$tenantA'; SELECT version FROM work_assistant_policy WHERE tenant_id = '$tenantA';")
    Assert-True ($policyRow -eq '2') "租户策略版本应为 2，实际 $policyRow"
    Add-Evidence 'device-revocation.txt' (@(
            '',
            '# 数据库断言',
            "work_assistant_segment 行数=$([int]$segmentCount)（注册两条 + 崩溃一条，重传被去重）",
            "work_assistant_device=$deviceRow",
            "work_assistant_policy version=$policyRow"
        ) -join "`n")

    Write-Host ''
    Write-Host '全部断言通过，证据已写入：'
    Get-ChildItem $evidence | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host
    Write-Host "临时目录：$work"
} finally {
    if (-not $KeepProcesses -and $apiProcess -and -not $apiProcess.HasExited) {
        Stop-Process -Id $apiProcess.Id -Force -ErrorAction SilentlyContinue
    }
}
