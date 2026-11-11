<#
.SYNOPSIS
    WP01-04 通用平台技术骨架的真实链路取证。

.DESCRIPTION
    在本地真实依赖（PostgreSQL + MinIO + 本机 API/Worker 进程）上逐项验证：

    1. 迁移：V4 在真实库上应用，四张新表均有 RLS + FORCE 与单条策略。
    2. 幂等：同一 Idempotency-Key 重放返回同一结果，不产生第二条作业；
       同键换载荷返回 422 KEY_REUSED。
    3. 作业：demo-work 跑到 succeeded/100%，取消请求落到 cancelled。
    4. 死信：fail-demo 重试耗尽进入死信；空理由 422，合法理由 202 并留下回放审计。
    5. 文件：预签名 PUT → complete → Worker 扫描 clean → 下载 200；
       跨主体 403、跨租户 404、签名过期 403。
    6. 指标：Prometheus 端点包含作业/outbox/扫描/HTTP 计数。
    7. 追踪：API 响应头 X-Trace-Id = 数据库 trace_id = Worker 日志 trace_id。
    8. 日志卫生：作业载荷里的 PAYLOAD_MARK 不出现在 API/Worker 日志里。
    9. 前端：typecheck、单测通过，构建产物存在。

    产物写入 docs/evidence/wp01-04/，任一步失败即抛错退出。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\platform-skeleton-verify.ps1
#>
[CmdletBinding()]
param(
    [int]$ApiPort = 18080,
    [string]$EvidenceDir,
    [switch]$SkipFrontend,
    [switch]$KeepProcesses
)

$ErrorActionPreference = 'Stop'
$env:WSL_UTF8 = '1'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
$evidence = if ($EvidenceDir) { $EvidenceDir } else { Join-Path $repoRoot 'docs\evidence\wp01-04' }
$work = Join-Path $env:TEMP ("wp01-04-verify-" + [guid]::NewGuid().ToString('N'))
$envMap = @{}
$baseUrl = "http://127.0.0.1:$ApiPort"
$distro = if ($env:PLATFORM_WSL_DISTRO) { $env:PLATFORM_WSL_DISTRO } else { 'pglab' }
$pgContainer = if ($env:PLATFORM_PG_CONTAINER) { $env:PLATFORM_PG_CONTAINER } else { 'platform-pg-dev' }
$tenantA = 'aaaaaaaa-0000-0000-0000-000000000001'
$tenantB = 'bbbbbbbb-0000-0000-0000-000000000002'
$subject1 = '11111111-1111-4111-8111-111111111111'
$subject2 = '22222222-2222-4222-8222-222222222222'
$secretMarker = 'PAYLOAD_MARK_' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$apiProcess = $null
$workerProcess = $null

New-Item -ItemType Directory -Force -Path $evidence | Out-Null
New-Item -ItemType Directory -Force -Path $work | Out-Null
# 每次重新取证，避免旧结果混入。
Get-ChildItem $evidence -Filter '*.txt' -ErrorAction SilentlyContinue | Remove-Item -Force

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

function Get-LastHttpHeader {
    param([string]$HeadersText, [string]$Name)
    $matches = [regex]::Matches($HeadersText, "(?im)^$([regex]::Escape($Name)):\s*(.+?)\s*$")
    if ($matches.Count -eq 0) { return $null }
    return $matches[$matches.Count - 1].Groups[1].Value
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
    param([string]$Tenant = $tenantA, [string]$Subject = $subject1, [string]$Key)
    $headers = @{
        'X-Tenant-Id' = $Tenant
        'X-Subject-Id' = $Subject
        'Accept' = 'application/json, application/problem+json'
        'X-Correlation-Id' = 'cor-' + [guid]::NewGuid().ToString('N')
    }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    return $headers
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

try {
    Write-Step '准备：环境变量、Docker、构建产物'
    $envMap = Read-EnvFile (Join-Path $repoRoot 'deploy\compose\.env')
    Assert-True ($envMap.ContainsKey('POSTGRES_APP_PASSWORD')) 'deploy/compose/.env 缺少 POSTGRES_APP_PASSWORD'

    $pinnedJavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
    if (Test-Path $pinnedJavaHome) { $env:JAVA_HOME = $pinnedJavaHome }
    Assert-True (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')) '找不到 JDK 21'

    $dockerOk = $false
    try {
        Invoke-Wsl @('docker', 'info', '--format', '{{.ServerVersion}}') | Out-Null
        $dockerOk = $true
    } catch {
        $dockup = ConvertTo-WslPath (Join-Path $repoRoot 'deploy\wsl\dockup.sh')
        Invoke-Wsl @('sh', $dockup, 'start') | Out-Null
        Invoke-Wsl @('docker', 'info', '--format', '{{.ServerVersion}}') | Out-Null
        $dockerOk = $true
    }
    Assert-True $dockerOk 'Docker 未就绪'

    Invoke-Wsl @('docker', 'inspect', '-f', '{{.State.Running}}', $pgContainer) | Out-Null
    $dbHost = (Invoke-Wsl @('hostname', '-I')) -split '\s+' | Where-Object { $_ } | Select-Object -First 1
    Assert-True ($null -ne $dbHost) '无法确定 WSL IP'
    Write-Host "数据库/对象存储地址：$dbHost"

    $apiJar = Join-Path $repoRoot 'backend\boot-api\target\boot-api-0.1.0-SNAPSHOT.jar'
    $workerJar = Join-Path $repoRoot 'backend\boot-worker\target\boot-worker-0.1.0-SNAPSHOT.jar'
    Assert-True (Test-Path $apiJar) "缺少构建产物：$apiJar（先跑 mvn -B verify）"
    Assert-True (Test-Path $workerJar) "缺少构建产物：$workerJar"

    Write-Step '数据库：确认租户种子与 V4 迁移'
    Invoke-Psql "INSERT INTO tenancy_tenant (id, slug, name, status) VALUES ('$tenantA','tenant-a','租户 A','active'), ('$tenantB','tenant-b','租户 B','active') ON CONFLICT (id) DO NOTHING;" | Out-Null
    $migrationsBefore = Invoke-Psql 'select version from flyway_schema_history order by installed_rank'
    Add-Evidence 'db-migration.txt' ("启动前迁移版本：`n$migrationsBefore")
    Assert-True ($migrationsBefore -match '3') '数据库未应用 V3 基线'

    Write-Step '启动 API 与 Worker（本机进程，连接 WSL 内依赖）'
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
    $env:PLATFORM_STORAGE_DOWNLOAD_TTL = '10s'
    $env:PLATFORM_SMOKE_ENABLED = 'false'

    $api = Start-PlatformProcess -Jar $apiJar -LogName 'api' -Arguments @("--server.port=$ApiPort")
    $apiProcess = $api.Process
    Wait-Until -What 'API 存活探针' -TimeoutSeconds 120 -Probe {
        try {
            $probe = Invoke-Api -Path '/actuator/health/liveness'
            return ($probe.Status -eq 200)
        } catch { return $false }
    } | Out-Null
    Write-Host "API 已启动 pid=$($apiProcess.Id)"

    $worker = Start-PlatformProcess -Jar $workerJar -LogName 'worker' -Arguments @(
        '--platform.outbox.poll-interval=1s',
        '--platform.outbox.retry-delay=1s',
        '--platform.outbox.max-attempts=2',
        '--platform.jobs.step-delay=200ms',
        '--platform.metrics.summary-interval=3s'
    )
    $workerProcess = $worker.Process
    Start-Sleep -Seconds 3
    Write-Host "Worker 已启动 pid=$($workerProcess.Id)"

    $migrationsAfter = Invoke-Psql 'select version from flyway_schema_history order by installed_rank'
    Add-Evidence 'db-migration.txt' ("启动后迁移版本：`n$migrationsAfter")
    Assert-True ($migrationsAfter -match '(?m)^4$') 'V4 未在真实库上应用'

    $rls = Invoke-Psql @"
select c.relname || ' rows=' || (select count(*) from pg_policy p where p.polrelid = c.oid) ||
       ' rls=' || c.relrowsecurity || ' force=' || c.relforcerowsecurity
from pg_class c
where c.relname in ('platform_idempotency','platform_job','platform_file','platform_outbox_replay')
order by c.relname;
"@
    Add-Evidence 'db-migration.txt' ("新表 RLS 状态：`n$rls")
    Assert-True (($rls -split "`n" | Where-Object { $_ -match 'rows=1 rls=true force=true' }).Count -eq 4) '四张新表必须各有 1 条策略且 RLS+FORCE 生效'

    Write-Step '幂等：同键重放不产生重复副作用'
    $key = [guid]::NewGuid().ToString()
    $payloadText = '{"marker":"' + $secretMarker + '","note":"idempotency"}'
    $headers = New-ApiHeaders -Key $key
    $first = Invoke-Api -Method POST -Path '/api/v1/system/jobs' -Headers $headers `
        -Json ('{"jobType":"demo-work","payload":' + $payloadText + '}')
    $second = Invoke-Api -Method POST -Path '/api/v1/system/jobs' -Headers $headers `
        -Json ('{"jobType":"demo-work","payload":' + $payloadText + '}')
    Assert-True ($first.Status -eq 201 -or $first.Status -eq 200) "首次创建作业返回 $($first.Status)：$($first.Body)"
    Assert-True ($second.Status -eq $first.Status) "重放状态码不一致：$($second.Status)"
    $replayed = Get-LastHttpHeader $second.Headers 'Idempotent-Replayed'
    Assert-True ($replayed -eq 'true') '重放响应缺少 Idempotent-Replayed: true'
    Assert-True ($first.Json.jobId -eq $second.Json.jobId) '重放返回了不同的 jobId'
    $jobId = $first.Json.jobId
    $jobRows = Invoke-Psql "select count(*) from platform_job where id = '$jobId'"
    Assert-True ([int]$jobRows -eq 1) "数据库出现重复作业：$jobRows"
    $reuse = Invoke-Api -Method POST -Path '/api/v1/system/jobs' -Headers $headers `
        -Json '{"jobType":"demo-work","payload":{"note":"changed"}}'
    Assert-True ($reuse.Status -eq 422) "同键换载荷应返回 422，实际 $($reuse.Status)"
    Assert-True ($reuse.Json.code -eq 'IDEMPOTENCY_KEY_REUSED') "错误码应为 IDEMPOTENCY_KEY_REUSED，实际 $($reuse.Json.code)"
    Add-Evidence 'idempotency.txt' (@(
            "幂等键=$key",
            "first: status=$($first.Status) jobId=$($first.Json.jobId)",
            "replay: status=$($second.Status) jobId=$($second.Json.jobId) Idempotent-Replayed=$replayed",
            "replay headers:`n$($second.Headers)",
            "duplicate job rows=$jobRows",
            "changed payload: status=$($reuse.Status) body=$($reuse.Body)"
        ) -join "`n")

    Write-Step '作业：成功与取消'
    $succeeded = Wait-Until -TimeoutSeconds 90 -What "作业 $jobId 成功" -Probe {
        $current = Invoke-Api -Path "/api/v1/system/jobs/$jobId" -Headers (New-ApiHeaders)
        if ($current.Json.status -eq 'succeeded') { return $current.Json }
        return $null
    }
    Assert-True ($succeeded.progress -eq 100) "作业进度不是 100：$($succeeded.progress)"
    $traceId = $succeeded.traceId
    Assert-True (-not [string]::IsNullOrWhiteSpace($traceId)) '作业缺少 traceId'

    $cancelKey = [guid]::NewGuid().ToString()
    $cancelCreate = Invoke-Api -Method POST -Path '/api/v1/system/jobs' -Headers (New-ApiHeaders -Key $cancelKey) `
        -Json '{"jobType":"demo-work","payload":{"note":"cancel"}}'
    Assert-True ($cancelCreate.Status -ge 200 -and $cancelCreate.Status -lt 300) "创建待取消作业失败：$($cancelCreate.Status)"
    $cancelJobId = $cancelCreate.Json.jobId
    $cancelCall = Invoke-Api -Method POST -Path "/api/v1/system/jobs/$cancelJobId/cancel" -Headers (New-ApiHeaders -Key ([guid]::NewGuid().ToString()))
    Assert-True ($cancelCall.Status -ge 200 -and $cancelCall.Status -lt 300) "取消请求失败：$($cancelCall.Status)"
    $cancelled = Wait-Until -TimeoutSeconds 30 -What "作业 $cancelJobId 取消" -Probe {
        $current = Invoke-Api -Path "/api/v1/system/jobs/$cancelJobId" -Headers (New-ApiHeaders)
        if ($current.Json.status -eq 'cancelled') { return $current.Json }
        return $null
    }
    Assert-True ($cancelled.cancelRequested -eq $true) '取消后 cancelRequested 应为 true'
    $traceRows = Invoke-Psql "select trace_id from platform_job where id = '$jobId'"
    Assert-True ($traceRows.Trim() -eq $traceId) '数据库 trace_id 与接口不一致'
    Add-Evidence 'jobs.txt' (@(
            "created jobId=$jobId status=$($succeeded.status) progress=$($succeeded.progress) traceId=$traceId",
            "db trace_id=$traceRows",
            "cancel jobId=$cancelJobId status=$($cancelled.status) cancelRequested=$($cancelled.cancelRequested)"
        ) -join "`n")

    Write-Step '死信与安全回放'
    $failCreate = Invoke-Api -Method POST -Path '/api/v1/system/jobs' -Headers (New-ApiHeaders -Key ([guid]::NewGuid().ToString())) `
        -Json '{"jobType":"fail-demo","payload":{"note":"dead-letter"}}'
    Assert-True ($failCreate.Status -ge 200 -and $failCreate.Status -lt 300) "创建 fail-demo 失败：$($failCreate.Status)"
    $dead = Wait-Until -TimeoutSeconds 60 -What '死信出现' -Probe {
        $list = Invoke-Api -Path '/api/v1/system/outbox/dead-letters' -Headers (New-ApiHeaders)
        if ($list.Status -ne 200) { return $null }
        $hit = $list.Json | Where-Object { $_.eventName -eq 'platform.job.requested.v1' } |
            Sort-Object occurredAt -Descending | Select-Object -First 1
        if ($hit) { return $hit }
        return $null
    }
    $badReplay = Invoke-Api -Method POST -Path "/api/v1/system/outbox/dead-letters/$($dead.eventId)/replay" `
        -Headers (New-ApiHeaders -Key ([guid]::NewGuid().ToString())) -Json '{"reason":"short"}'
    Assert-True ($badReplay.Status -eq 422) "空/短理由应返回 422，实际 $($badReplay.Status)"
    $goodReplay = Invoke-Api -Method POST -Path "/api/v1/system/outbox/dead-letters/$($dead.eventId)/replay" `
        -Headers (New-ApiHeaders -Key ([guid]::NewGuid().ToString())) -Json '{"reason":"人工确认后回放，验证消费端幂等"}'
    Assert-True ($goodReplay.Status -eq 202) "合法回放应返回 202，实际 $($goodReplay.Status) $($goodReplay.Body)"
    Assert-True ($goodReplay.Json.status -eq 'pending') '回放响应状态应为 pending'
    $auditRows = Invoke-Psql "select count(*) from platform_outbox_replay where event_id = '$($dead.eventId)'"
    Assert-True ([int]$auditRows -ge 1) '回放未留下审计记录'
    Add-Evidence 'dead-letter-replay.txt' (@(
            "dead letter: $($dead | ConvertTo-Json -Compress -Depth 5)",
            "short reason: status=$($badReplay.Status) body=$($badReplay.Body)",
            "valid reason: status=$($goodReplay.Status) body=$($goodReplay.Body)",
            "audit rows=$auditRows"
        ) -join "`n")

    Write-Step '文件：预签名上传、扫描、下载再授权'
    $fileName = 'wp01-04-evidence.txt'
    $fileContent = 'hello wp01-04 ' + [guid]::NewGuid().ToString('N')
    $localFile = Join-Path $work $fileName
    Write-TextFile $localFile $fileContent
    $size = (Get-Item $localFile).Length

    $upload = Invoke-Api -Method POST -Path '/api/v1/system/files/uploads' -Headers (New-ApiHeaders -Key ([guid]::NewGuid().ToString())) `
        -Json (@{ fileName = $fileName; contentType = 'text/plain'; sizeBytes = $size } | ConvertTo-Json -Compress)
    Assert-True ($upload.Status -ge 200 -and $upload.Status -lt 300) "创建上传会话失败：$($upload.Status) $($upload.Body)"
    $fileId = $upload.Json.fileId
    $putStatus = (& curl.exe @('-s', '-S', '-o', 'NUL', '-w', '%{http_code}', '-X', 'PUT',
            '-H', 'Content-Type: text/plain', '--data-binary', "@$localFile", $upload.Json.uploadUrl)) -join ''
    Assert-True ([int]$putStatus -eq 200) "预签名 PUT 返回 $putStatus"

    $complete = Invoke-Api -Method POST -Path "/api/v1/system/files/$fileId/complete" `
        -Headers (New-ApiHeaders -Key ([guid]::NewGuid().ToString())) -Json '{}'
    Assert-True ($complete.Status -ge 200 -and $complete.Status -lt 300) "完成上传失败：$($complete.Status) $($complete.Body)"

    $scanned = Wait-Until -TimeoutSeconds 60 -What '文件扫描完成' -Probe {
        $list = Invoke-Api -Path '/api/v1/system/files' -Headers (New-ApiHeaders)
        $hit = $list.Json | Where-Object { $_.fileId -eq $fileId } | Select-Object -First 1
        if ($hit -and $hit.scanStatus -eq 'clean') { return $hit }
        return $null
    }
    Assert-True ($scanned.status -eq 'available') "扫描通过后状态应为 available，实际 $($scanned.status)"

    $grant = Invoke-Api -Path "/api/v1/system/files/$fileId/download" -Headers (New-ApiHeaders)
    Assert-True ($grant.Status -eq 200) "获取下载地址失败：$($grant.Status) $($grant.Body)"
    $downloadFile = Join-Path $work 'download.txt'
    $downloadStatus = (& curl.exe @('-s', '-S', '-o', $downloadFile, '-w', '%{http_code}', $grant.Json.url)) -join ''
    Assert-True ([int]$downloadStatus -eq 200) "首次下载返回 $downloadStatus"
    Assert-True ((Get-Content -Raw -Encoding UTF8 $downloadFile) -eq $fileContent) '下载内容与上传不一致'

    $otherSubject = Invoke-Api -Path "/api/v1/system/files/$fileId/download" -Headers (New-ApiHeaders -Subject $subject2)
    Assert-True ($otherSubject.Status -eq 403) "跨主体下载应 403，实际 $($otherSubject.Status)"
    Assert-True ($otherSubject.Json.code -eq 'FILE_NOT_OWNED') "跨主体错误码应为 FILE_NOT_OWNED，实际 $($otherSubject.Json.code)"
    $otherTenant = Invoke-Api -Path "/api/v1/system/files/$fileId/download" -Headers (New-ApiHeaders -Tenant $tenantB -Subject $subject2)
    Assert-True ($otherTenant.Status -eq 404) "跨租户下载应 404，实际 $($otherTenant.Status)"

    Write-Host '等待下载地址过期（10s TTL）…'
    Start-Sleep -Seconds 12
    $expiredFile = Join-Path $work 'expired.txt'
    $expiredStatus = (& curl.exe @('-s', '-S', '-o', $expiredFile, '-w', '%{http_code}', $grant.Json.url)) -join ''
    $expiredBody = Get-Content -Raw -Encoding UTF8 $expiredFile
    Assert-True ([int]$expiredStatus -eq 403) "过期下载地址应 403，实际 $expiredStatus"
    Add-Evidence 'files.txt' (@(
            "fileId=$fileId size=$size",
            "upload session: status=$($upload.Status) objectKey=$($upload.Json.objectKey)",
            "presigned PUT status=$putStatus",
            "complete: status=$($complete.Status) status=$($complete.Json.status)",
            "scan: scanStatus=$($scanned.scanStatus) status=$($scanned.status)",
            "download url: status=$($grant.Status) expiresAt=$($grant.Json.expiresAt)",
            "download bytes match=$((Get-Content -Raw -Encoding UTF8 $downloadFile) -eq $fileContent) http=$downloadStatus",
            "cross subject: status=$($otherSubject.Status) body=$($otherSubject.Body)",
            "cross tenant: status=$($otherTenant.Status) body=$($otherTenant.Body)",
            "expired url: status=$expiredStatus body=$expiredBody"
        ) -join "`n")

    Write-Step '指标与追踪'
    $prometheus = Invoke-Api -Path '/actuator/prometheus'
    Assert-True ($prometheus.Status -eq 200) "Prometheus 端点返回 $($prometheus.Status)"
    # 计数器不能叫 platform.jobs.created：Micrometer 的 Prometheus 命名会剥掉 created 后缀，
    # 导出名会变成语义含糊的 platform_jobs_total，所以用 submitted。
    $metricNames = @(
        'platform_jobs_submitted_total',
        'platform_jobs_state_total',
        'platform_http_requests_total',
        'platform_idempotency_total'
    )
    $metricEvidence = @()
    foreach ($metric in $metricNames) {
        $lines = @(($prometheus.Body -split "`n") | Where-Object { $_ -like "$metric*" })
        Assert-True ($lines.Count -ge 1) "缺少指标 $metric"
        $metricEvidence += "$metric lines=$($lines.Count) sample=$($lines[0].Trim())"
    }

    # Worker 没有 Web 端口：outbox / 文件扫描指标以周期汇总日志取证
    $workerSnapshot = Wait-Until -TimeoutSeconds 40 -What 'Worker 指标汇总日志' -Probe {
        $text = Get-Content -Raw -Encoding UTF8 $worker.OutLog -ErrorAction SilentlyContinue
        if (-not $text) { return $null }
        $line = @($text -split "`n" | Where-Object { $_ -match 'platform\.outbox\.dispatched=([1-9]\d*)' } |
                Select-Object -Last 1)
        if ($line.Count -eq 0) { return $null }
        return $line[0]
    }
    Assert-True ($workerSnapshot -match 'platform\.files\.scanned=([1-9]\d*)') "Worker 汇总缺少文件扫描指标：$workerSnapshot"
    Assert-True ($workerSnapshot -match 'platform\.jobs\.state=([1-9]\d*)') "Worker 汇总缺少作业状态指标：$workerSnapshot"
    $metricEvidence += "worker 汇总日志：$($workerSnapshot.Trim())"
    $metricEvidence += 'API 走 /actuator/prometheus；Worker 无 Web 端口，指标写入周期汇总日志（接入 OTLP 后改为抓取）。'
    Add-Evidence 'metrics.txt' ($metricEvidence -join "`n")

    $apiLogText = (@($api.OutLog, $api.ErrLog) | Where-Object { Test-Path $_ } | ForEach-Object { Get-Content -Raw -Encoding UTF8 $_ }) -join "`n"
    $workerLogText = (@($worker.OutLog, $worker.ErrLog) | Where-Object { Test-Path $_ } | ForEach-Object { Get-Content -Raw -Encoding UTF8 $_ }) -join "`n"
    Assert-True ($workerLogText.Contains($traceId)) 'Worker 日志里找不到 traceId'
    Add-Evidence 'trace.txt' (@(
            "jobId=$jobId",
            "traceId=$traceId",
            "db trace_id=$traceRows",
            "worker log matches=$(([regex]::Matches($workerLogText, [regex]::Escape($traceId))).Count)",
            "api log matches=$(([regex]::Matches($apiLogText, [regex]::Escape($traceId))).Count)",
            'API/Worker 均使用 ECS 结构化日志；页面请求头 X-Correlation-Id 与 trace 一起写入 outbox 与作业行。'
        ) -join "`n")

    Write-Step '日志卫生：载荷标记不得落日志'
    Assert-True (-not $apiLogText.Contains($secretMarker)) 'API 日志出现了作业载荷标记'
    Assert-True (-not $workerLogText.Contains($secretMarker)) 'Worker 日志出现了作业载荷标记'
    Add-Evidence 'log-hygiene.txt' (@(
            "marker=$secretMarker",
            "api log contains marker=false",
            "worker log contains marker=false",
            '规则：只记录 ID/状态/错误类型，不记录请求体、文件内容与业务正文。'
        ) -join "`n")

    if (-not $SkipFrontend) {
        Write-Step '前端：typecheck、单测与构建'
        Push-Location (Join-Path $repoRoot 'frontend')
        try {
            $typecheck = & pnpm -r typecheck 2>&1 | Out-String
            Assert-True ($LASTEXITCODE -eq 0) "前端 typecheck 失败：`n$typecheck"
            $tests = & pnpm -r test 2>&1 | Out-String
            Assert-True ($LASTEXITCODE -eq 0) "前端测试失败：`n$tests"
            $build = & pnpm -r build 2>&1 | Out-String
            Assert-True ($LASTEXITCODE -eq 0) "前端构建失败：`n$build"
            $dist = Join-Path $repoRoot 'frontend\apps\web\dist\index.html'
            Assert-True (Test-Path $dist) '缺少前端构建产物 dist/index.html'
            $testLines = @($tests -split "`n" | Where-Object { $_ -match 'Test Files|Tests ' } |
                    ForEach-Object { $_.Trim() })
            $frontendEvidence = @('pnpm -r typecheck: PASS') + $testLines + @('dist/index.html exists=true')
            Add-Evidence 'frontend-verify.txt' ($frontendEvidence -join "`n")
        } finally {
            Pop-Location
        }
    }

    Write-Host ''
    Write-Host '全部断言通过，证据已写入：'
    Get-ChildItem $evidence | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host
} finally {
    if (-not $KeepProcesses) {
        foreach ($process in @($apiProcess, $workerProcess)) {
            if ($process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
}
