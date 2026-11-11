<#
.SYNOPSIS
    数据库迁移、RLS、并发、索引与锁等待验证（WP01-03）。

.DESCRIPTION
    在真实 PostgreSQL 上跑完整套检查，产物是可直接归档的文本证据：

    1. 空库升级：迁移到最新版本并通过 validate。
    2. 上一受支持版本升级：先迁到 V2，灌 50 万行 outbox，再迁到 V3；
       断言 V1/V2 校验和与空库一致，V3 生效。
    3. 校验和门禁：篡改 flyway_schema_history 的 checksum，validate 必须失败；
       还原后必须通过。
    4. RLS 正例与负例：无上下文、伪造租户、跨租户读写、空串上下文、
       事务提交后上下文不残留、运行期账号不能提权。
    5. 并发认领：两个独立会话同时执行 FOR UPDATE SKIP LOCKED，不得拿到同一行。
    6. 索引与执行计划：投递扫描必须走部分索引。
    7. 迁移期间的锁等待评估：V3 执行时持续做短事务写入，统计最慢语句。
    8. 连接池：HikariCP 借用同一条物理连接时，租户上下文不得跨事务残留。

    本地模式自动回退到 WSL 内的 Docker 与容器内 psql；CI 里直接用原生 psql。
    失败即抛出非零退出，不做「警告后继续」。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1
#>
[CmdletBinding()]
param(
    [string]$DbHost = 'localhost',
    [int]$Port = 5432,
    [string]$OwnerUser = 'platform_owner',
    [string]$AppUser = 'platform_app',
    [string]$OwnerPassword,
    [string]$AppPassword,
    [switch]$KeepDatabases
)

$ErrorActionPreference = 'Stop'
# wsl.exe 默认用 UTF-16 输出提示，管道里会混入 NUL 字节；强制 UTF-8 才能可靠过滤告警
$env:WSL_UTF8 = '1'
# Windows PowerShell 5.1 默认按系统代码页解码原生命令的输出，SQL 里的中文提示会变成问号；
# 统一成 UTF-8。写入管道的编码必须无 BOM，否则 psql 会把 BOM 当成 SQL 的第一个字符。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeDir = Join-Path $repoRoot 'deploy\compose'
$casesDir = Join-Path $repoRoot 'tests\db'
$pinnedJavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$emptyDb = 'platform_verify_empty'
$upgradeDb = 'platform_verify_upgrade'

function Write-Step {
    param([string]$Text)
    Write-Host ''
    Write-Host "== $Text"
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "断言失败：$Message" }
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

function Resolve-PgClient {
    $native = Get-Command psql -ErrorAction SilentlyContinue
    if ($native) {
        return [pscustomobject]@{ Mode = 'native'; Distro = $null; Container = $null }
    }
    $distro = if ($env:PLATFORM_WSL_DISTRO) { $env:PLATFORM_WSL_DISTRO } else { 'pglab' }
    $container = if ($env:PLATFORM_PG_CONTAINER) { $env:PLATFORM_PG_CONTAINER } else { 'platform-pg-dev' }
    # 原生命令的 stderr（含 WSL 代理告警）不应中断脚本，交给退出码判定
    $ErrorActionPreference = 'Continue'
    $state = & wsl -d $distro -u root -- docker inspect -f '{{.State.Running}}' $container 2>$null
    if ($LASTEXITCODE -ne 0 -or ($state -join '') -notmatch 'true') {
        throw "找不到 psql，且 WSL($distro) 内容器 $container 未运行。先跑 scripts\dev-up.ps1"
    }
    return [pscustomobject]@{ Mode = 'docker'; Distro = $distro; Container = $container }
}

function Invoke-Psql {
    param(
        [string]$Database,
        [string]$User,
        [string]$Password,
        [string]$Sql,
        [switch]$AllowFailure
    )
    $ErrorActionPreference = 'Continue'
    if ($script:pg.Mode -eq 'native') {
        $env:PGPASSWORD = $Password
        $out = $Sql | & psql -h $DbHost -p $Port -U $User -d $Database -v ON_ERROR_STOP=1 -t -A 2>&1
    } else {
        # 容器内用 127.0.0.1:5432：宿主机映射端口与容器内端口不是一回事
        $out = $Sql | & wsl -d $script:pg.Distro -u root -- docker exec -i `
                -e "PGPASSWORD=$Password" -e 'PGCLIENTENCODING=UTF8' $script:pg.Container `
                psql -h 127.0.0.1 -p 5432 -U $User -d $Database -v ON_ERROR_STOP=1 -t -A 2>&1
    }
    $code = $LASTEXITCODE
    # WSL 每次调用都会在 stderr 打印一条 localhost 代理告警，不能混进 SQL 结果里
    $text = (($out | ForEach-Object { "$_" }) | Where-Object { $_ -notmatch 'wsl:' }) -join "`n"
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "psql 失败（exit=$code, db=$Database）：`n$text"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Invoke-SqlFile {
    param([string]$Database, [string]$User, [string]$Password, [string]$FileName, [switch]$AllowFailure)
    $path = Join-Path $casesDir $FileName
    $sql = Get-Content -Raw -Encoding UTF8 $path
    return Invoke-Psql -Database $Database -User $User -Password $Password -Sql $sql -AllowFailure:$AllowFailure
}

function Invoke-Flyway {
    param(
        [ValidateSet('migrate', 'validate', 'info')][string]$Goal,
        [string]$Database,
        [string]$Target,
        [switch]$ExpectFailure
    )
    if (Test-Path $pinnedJavaHome) { $env:JAVA_HOME = $pinnedJavaHome }
    $mvnArgs = @(
        '-B', '-ntp', '-f', (Join-Path $repoRoot 'backend\db\pom.xml'), "flyway:$Goal",
        "-Dflyway.url=jdbc:postgresql://$($DbHost):$Port/$Database",
        "-Dflyway.user=$OwnerUser",
        "-Dflyway.password=$OwnerPassword",
        # V3 的 CREATE INDEX CONCURRENTLY 需要事务外执行；Flyway 默认把咨询锁放在一个未提交事务里，
        # 会让并发建索引等这个事务等到超时（本地实测永久挂起）。关掉事务级锁，改用会话级咨询锁。
        '-Dflyway.postgresql.transactional.lock=false'
    )
    if ($Target) { $mvnArgs += "-Dflyway.target=$Target" }
    if ($env:PLATFORM_MAVEN_REPO) { $mvnArgs += "-Dmaven.repo.local=$($env:PLATFORM_MAVEN_REPO)" }

    $ErrorActionPreference = 'Continue'
    $out = & mvn @mvnArgs 2>&1
    $code = $LASTEXITCODE
    $text = ($out | ForEach-Object { "$_" }) -join "`n"
    if ($ExpectFailure) {
        if ($code -eq 0) { throw "flyway:$Goal 本应失败却成功了（门禁失效）：`n$text" }
        Write-Host "  flyway:$Goal 按预期失败（exit=$code）"
    } elseif ($code -ne 0) {
        throw "flyway:$Goal 失败（exit=$code, db=$Database）：`n$text"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Recreate-Database {
    param([string]$Database)
    Invoke-Psql -Database 'postgres' -User $OwnerUser -Password $OwnerPassword -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();
"@ | Out-Null
    Invoke-Psql -Database 'postgres' -User $OwnerUser -Password $OwnerPassword -Sql "DROP DATABASE IF EXISTS $Database;" | Out-Null
    Invoke-Psql -Database 'postgres' -User $OwnerUser -Password $OwnerPassword -Sql "CREATE DATABASE $Database;" | Out-Null
}

function Grant-AppRole {
    param([string]$Database)
    # 默认权限是按库生效的：新建的演练库必须重新授权，否则 RLS 用例会先撞上 permission denied
    Invoke-Psql -Database $Database -User $OwnerUser -Password $OwnerPassword -Sql @"
GRANT USAGE ON SCHEMA public TO $AppUser;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $AppUser;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $AppUser;
"@ | Out-Null
}

function Get-Checksums {
    param([string]$Database)
    $result = Invoke-Psql -Database $Database -User $OwnerUser -Password $OwnerPassword -Sql @"
SELECT version || '|' || checksum
  FROM flyway_schema_history
 WHERE version IS NOT NULL
 ORDER BY installed_rank;
"@
    return (($result.Output -split "`r?`n") | Where-Object { $_ -match '^\d+\|-?\d+$' })
}

function Get-Uuid {
    param([string]$Text)
    $match = [regex]::Match($Text, '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
    if ($match.Success) { return $match.Value }
    return $null
}

$envMap = Read-EnvFile (Join-Path $composeDir '.env')
if (-not $OwnerPassword) {
    $OwnerPassword = if ($env:PLATFORM_OWNER_PASSWORD) { $env:PLATFORM_OWNER_PASSWORD } else { $envMap['POSTGRES_OWNER_PASSWORD'] }
}
if (-not $AppPassword) {
    $AppPassword = if ($env:PLATFORM_APP_PASSWORD) { $env:PLATFORM_APP_PASSWORD } else { $envMap['POSTGRES_APP_PASSWORD'] }
}
if (-not $OwnerPassword -or -not $AppPassword) {
    throw '缺少数据库口令：设置 PLATFORM_OWNER_PASSWORD / PLATFORM_APP_PASSWORD，或准备 deploy\compose\.env'
}

$script:pg = Resolve-PgClient
# WSL 为 NAT 模式时宿主机的 localhost 不会转发到发行版内部，JDBC 必须用发行版 IP；
# psql 仍然在容器内执行，走容器自己的 127.0.0.1，两者互不影响。
if ($script:pg.Mode -eq 'docker' -and $DbHost -eq 'localhost') {
    $wslIp = (& wsl -d $script:pg.Distro -u root -- hostname -I) -split '\s+' | Where-Object { $_ } | Select-Object -First 1
    if ($wslIp) { $DbHost = $wslIp.Trim() }
}
Write-Host ("psql 客户端：{0}" -f $(if ($script:pg.Mode -eq 'native') { '原生 psql' } else { "WSL($($script:pg.Distro)) 容器 $($script:pg.Container)" }))
Write-Host ("目标：{0}:{1}  owner={2}  app={3}" -f $DbHost, $Port, $OwnerUser, $AppUser)

try {
    # ---------------------------------------------------------------- 1 空库升级
    Write-Step '1 空库升级：DROP/CREATE 演练库 → migrate → validate'
    Recreate-Database $emptyDb
    Invoke-Flyway -Goal migrate -Database $emptyDb | Out-Null
    Invoke-Flyway -Goal validate -Database $emptyDb | Out-Null
    Grant-AppRole $emptyDb

    $emptyChecksums = Get-Checksums $emptyDb
    Write-Host '  校验和（空库）：'
    $emptyChecksums | ForEach-Object { Write-Host "    $_" }
    Assert-True ($emptyChecksums.Count -eq 6) "空库应安装 6 个版本，实际 $($emptyChecksums.Count)"

    # ---------------------------------------------------------------- 2 RLS
    Write-Step '2 RLS 正向与负例（platform_app 身份）'
    Invoke-SqlFile -Database $emptyDb -User $OwnerUser -Password $OwnerPassword -FileName '00-seed-two-tenants.sql' | Out-Null
    $rls = Invoke-SqlFile -Database $emptyDb -User $AppUser -Password $AppPassword -FileName '10-rls-cases.sql'
    Write-Host $rls.Output

    # ---------------------------------------------------------------- 3 并发认领
    Write-Step '3 并发认领：两个独立会话同时 FOR UPDATE SKIP LOCKED'
    $holdSql = @'
BEGIN;
SELECT event_id FROM platform_outbox
 WHERE status IN ('pending','failed') AND available_at <= now()
 ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED;
SELECT pg_sleep(3);
COMMIT;
'@
    $claimSql = @'
BEGIN;
SELECT event_id FROM platform_outbox
 WHERE status IN ('pending','failed') AND available_at <= now()
 ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED;
COMMIT;
'@
    $holdJob = Start-Job -ScriptBlock {
        param($ctx, $sql)
        $ErrorActionPreference = 'Continue'
        if ($ctx.Mode -eq 'native') {
            $env:PGPASSWORD = $ctx.Password
            $sql | & psql -h $ctx.Host -p $ctx.Port -U $ctx.User -d $ctx.Database -v ON_ERROR_STOP=1 -t -A 2>&1
        } else {
            $sql | & wsl -d $ctx.Distro -u root -- docker exec -i -e "PGPASSWORD=$($ctx.Password)" $ctx.Container `
                    psql -h 127.0.0.1 -p 5432 -U $ctx.User -d $ctx.Database -v ON_ERROR_STOP=1 -t -A 2>&1
        }
    } -ArgumentList @(
        [pscustomobject]@{
            Mode = $script:pg.Mode; Distro = $script:pg.Distro; Container = $script:pg.Container
            Host = $DbHost; Port = $Port; User = $AppUser; Password = $AppPassword; Database = $emptyDb
        }, $holdSql
    )
    Start-Sleep -Seconds 2
    $second = Invoke-Psql -Database $emptyDb -User $AppUser -Password $AppPassword -Sql $claimSql
    $null = Wait-Job $holdJob -Timeout 30
    $firstOutput = (Receive-Job $holdJob) -join "`n"
    Remove-Job $holdJob -Force

    $firstId = Get-Uuid $firstOutput
    $secondId = Get-Uuid $second.Output
    Assert-True ($firstId -and $secondId) "并发认领未返回事件 ID：first=$firstId second=$secondId"
    Assert-True ($firstId -ne $secondId) "两个会话认领到同一行 $firstId，SKIP LOCKED 失效"
    Write-Host "  会话 1 持有：$firstId"
    Write-Host "  会话 2 取得：$secondId（不同行，通过）"

    # ---------------------------------------------------------------- 4 上一版本升级
    Write-Step '4 上一受支持版本升级：migrate 到 V2 → 灌 50 万行 → migrate 到最新'
    Recreate-Database $upgradeDb
    Invoke-Flyway -Goal migrate -Database $upgradeDb -Target '2' | Out-Null
    $beforeChecksums = Get-Checksums $upgradeDb
    Write-Host '  V2 时的校验和：'
    $beforeChecksums | ForEach-Object { Write-Host "    $_" }

    $seededAt = Get-Date
    Invoke-SqlFile -Database $upgradeDb -User $OwnerUser -Password $OwnerPassword -FileName '01-seed-bulk.sql' | Out-Null
    $seedSeconds = [int]((Get-Date) - $seededAt).TotalSeconds
    $rowCount = Invoke-Psql -Database $upgradeDb -User $OwnerUser -Password $OwnerPassword -Sql 'SELECT count(*) FROM platform_outbox;'
    Write-Host ("  已灌入 outbox 行数：{0}（{1}s）" -f $rowCount.Output.Trim(), $seedSeconds)
    Grant-AppRole $upgradeDb

    # 迁移期间持续短事务写入，用真实语句耗时判断是否被锁阻塞
    $writerSql = Get-Content -Raw -Encoding UTF8 (Join-Path $casesDir '50-lock-probe-writer.sql')
    $writerJob = Start-Job -ScriptBlock {
        param($ctx, $sql)
        $ErrorActionPreference = 'Continue'
        if ($ctx.Mode -eq 'native') {
            $env:PGPASSWORD = $ctx.Password
            $sql | & psql -h $ctx.Host -p $ctx.Port -U $ctx.User -d $ctx.Database -v ON_ERROR_STOP=1 -t -A 2>&1
        } else {
            $sql | & wsl -d $ctx.Distro -u root -- docker exec -i -e "PGPASSWORD=$($ctx.Password)" $ctx.Container `
                    psql -h 127.0.0.1 -p 5432 -U $ctx.User -d $ctx.Database -v ON_ERROR_STOP=1 -t -A 2>&1
        }
    } -ArgumentList @(
        [pscustomobject]@{
            Mode = $script:pg.Mode; Distro = $script:pg.Distro; Container = $script:pg.Container
            Host = $DbHost; Port = $Port; User = $AppUser; Password = $AppPassword; Database = $upgradeDb
        }, $writerSql
    )
    Start-Sleep -Seconds 2

    $migrateStarted = Get-Date
    Invoke-Flyway -Goal migrate -Database $upgradeDb | Out-Null
    $migrateSeconds = [math]::Round(((Get-Date) - $migrateStarted).TotalSeconds, 1)
    $null = Wait-Job $writerJob -Timeout 120
    $writerOutput = (Receive-Job $writerJob) -join "`n"
    Remove-Job $writerJob -Force

    $times = @([regex]::Matches($writerOutput, 'Time:\s*([0-9.]+)\s*ms') | ForEach-Object { [double]$_.Groups[1].Value })
    $errors = @([regex]::Matches($writerOutput, '(?m)^ERROR'))
    Assert-True ($errors.Count -eq 0) "迁移期间出现 $($errors.Count) 条写入错误：`n$writerOutput"
    Assert-True ($times.Count -ge 100) "锁探针语句数过少（$($times.Count)），无法评估"
    $maxMs = ($times | Measure-Object -Maximum).Maximum
    $overOneSecond = @($times | Where-Object { $_ -ge 1000 }).Count
    Write-Host ("  迁移耗时 {0}s；探针执行 {1} 条语句，最慢 {2:N1} ms，超过 1s 的语句 {3} 条" -f `
            $migrateSeconds, $times.Count, $maxMs, $overOneSecond)

    Invoke-Flyway -Goal validate -Database $upgradeDb | Out-Null
    $afterChecksums = Get-Checksums $upgradeDb
    Write-Host '  升级后的校验和：'
    $afterChecksums | ForEach-Object { Write-Host "    $_" }

    foreach ($line in $beforeChecksums) {
        Assert-True ($emptyChecksums -contains $line) "上一版本校验和与空库不一致（历史文件被改？）：$line"
        Assert-True ($afterChecksums -contains $line) "升级后校验和变化：$line"
    }
    Assert-True ($afterChecksums.Count -eq 6) "升级后应有 6 个版本，实际 $($afterChecksums.Count)"
    # V6 在授权之后创建新表，重新授权确保平台应用账号可读写租户生命周期表
    Grant-AppRole $upgradeDb

    # ---------------------------------------------------------------- 5 结构与索引
    Write-Step '5 迁移后结构与一致性、索引执行计划'
    $integrity = Invoke-SqlFile -Database $upgradeDb -User $AppUser -Password $AppPassword -FileName '40-integrity.sql'
    Write-Host $integrity.Output
    $plan = Invoke-SqlFile -Database $upgradeDb -User $AppUser -Password $AppPassword -FileName '30-index-plan.sql'
    Write-Host $plan.Output
    Assert-True ($plan.Output -match 'platform_outbox_dispatch_idx') '投递扫描未使用部分索引 platform_outbox_dispatch_idx'
    Assert-True ($plan.Output -notmatch 'Seq Scan on platform_outbox') '投递扫描退化为全表扫描'

    # ---------------------------------------------------------------- 5b 租户生命周期
    Write-Step '5b 租户生命周期：RLS、同号并存、约束与状态机'
    $lifecycle = Invoke-SqlFile -Database $upgradeDb -User $AppUser -Password $AppPassword -FileName '60-tenant-lifecycle.sql'
    Write-Host $lifecycle.Output
    Assert-True ($lifecycle.Output -match 'WP02-01 租户生命周期数据库断言通过') '生命周期 SQL 断言未通过'

    if (Test-Path $pinnedJavaHome) { $env:JAVA_HOME = $pinnedJavaHome }
    $lifecycleArgs = @(
        '-B', '-ntp', '-q', '-f', (Join-Path $repoRoot 'backend\modules\tenancy\pom.xml'), 'test',
        '-Dtest=TenantLifecycleDbTest', '-DfailIfNoTests=true',
        '-Djacoco.skip=true',
        "-Dplatform.db.url=jdbc:postgresql://$($DbHost):$Port/$upgradeDb",
        "-Dplatform.db.user=$AppUser",
        "-Dplatform.db.password=$AppPassword"
    )
    if ($env:PLATFORM_MAVEN_REPO) { $lifecycleArgs += "-Dmaven.repo.local=$($env:PLATFORM_MAVEN_REPO)" }
    $ErrorActionPreference = 'Continue'
    $lifecycleOut = & mvn @lifecycleArgs 2>&1
    $lifecycleCode = $LASTEXITCODE
    if ($lifecycleCode -ne 0) {
        throw "租户生命周期用例失败（exit=$lifecycleCode）：`n$((($lifecycleOut | ForEach-Object { "$_" }) -join "`n"))"
    }
    $lifecycleReport = Join-Path $repoRoot 'backend\modules\tenancy\target\surefire-reports\com.acme.platform.tenancy.TenantLifecycleDbTest.txt'
    Assert-True (Test-Path $lifecycleReport) "找不到生命周期用例报告：$lifecycleReport"
    $lifecycleSummary = (Get-Content -Encoding UTF8 $lifecycleReport | Where-Object { $_ -match '^Tests run:' } | Select-Object -First 1)
    Assert-True ($lifecycleSummary -match 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0') "生命周期用例未执行：$lifecycleSummary"
    Write-Host "  $lifecycleSummary"
    Write-Host '  状态机、冻结门禁、冷静期删除、审计链、同号跨租户与支持访问时限全部通过'

    # ---------------------------------------------------------------- 6 校验和门禁
    Write-Step '6 校验和门禁：篡改历史 checksum 后 validate 必须失败'
    Invoke-Psql -Database $upgradeDb -User $OwnerUser -Password $OwnerPassword -Sql `
        "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '2';" | Out-Null
    Invoke-Flyway -Goal validate -Database $upgradeDb -ExpectFailure | Out-Null
    Invoke-Psql -Database $upgradeDb -User $OwnerUser -Password $OwnerPassword -Sql `
        "UPDATE flyway_schema_history SET checksum = checksum - 1 WHERE version = '2';" | Out-Null
    Invoke-Flyway -Goal validate -Database $upgradeDb | Out-Null
    Write-Host '  篡改被拦截，还原后 validate 通过'

    # ---------------------------------------------------------------- 7 连接池
    Write-Step '7 连接池：租户上下文随连接复用不得残留'
    if (Test-Path $pinnedJavaHome) { $env:JAVA_HOME = $pinnedJavaHome }
    $poolArgs = @(
        '-B', '-ntp', '-q', '-f', (Join-Path $repoRoot 'backend\db\pom.xml'), 'test',
        '-Dtest=ConnectionPoolTenantContextTest', '-DfailIfNoTests=true',
        # 工作区路径含中文时 JaCoCo agent 无法落盘；这里只验证语义，不度量覆盖率
        '-Djacoco.skip=true',
        "-Dplatform.db.url=jdbc:postgresql://$($DbHost):$Port/$upgradeDb",
        "-Dplatform.db.user=$AppUser",
        "-Dplatform.db.password=$AppPassword"
    )
    if ($env:PLATFORM_MAVEN_REPO) { $poolArgs += "-Dmaven.repo.local=$($env:PLATFORM_MAVEN_REPO)" }
    $ErrorActionPreference = 'Continue'
    $poolOut = & mvn @poolArgs 2>&1
    $poolCode = $LASTEXITCODE
    if ($poolCode -ne 0) {
        throw "连接池用例失败（exit=$poolCode）：`n$((($poolOut | ForEach-Object { "$_" }) -join "`n"))"
    }
    # 用例在缺库时会被跳过，退出码同样是 0；必须读报告断言真的跑了 3 条
    $poolReport = Join-Path $repoRoot 'backend\db\target\surefire-reports\com.acme.platform.db.ConnectionPoolTenantContextTest.txt'
    Assert-True (Test-Path $poolReport) "找不到连接池用例报告：$poolReport"
    $poolSummary = (Get-Content -Encoding UTF8 $poolReport | Where-Object { $_ -match '^Tests run:' } | Select-Object -First 1)
    Assert-True ($poolSummary -match 'Tests run: 3, Failures: 0, Errors: 0, Skipped: 0') "连接池用例未全部执行：$poolSummary"
    Write-Host "  $poolSummary"
    Write-Host '  事务级 SET LOCAL 提交后不残留；会话级 SET 会泄漏（故禁用）；两个连接并发各看各租户'

    Write-Host ''
    Write-Host '全部检查通过：空库升级、上一版本升级、校验和门禁、RLS、租户生命周期、并发、索引、锁等待评估、连接池隔离。'
} finally {
    if (-not $KeepDatabases -and $script:pg) {
        foreach ($db in @($emptyDb, $upgradeDb)) {
            try {
                Invoke-Psql -Database 'postgres' -User $OwnerUser -Password $OwnerPassword -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$db' AND pid <> pg_backend_pid();
"@ | Out-Null
                Invoke-Psql -Database 'postgres' -User $OwnerUser -Password $OwnerPassword -Sql "DROP DATABASE IF EXISTS $db;" | Out-Null
            } catch {
                Write-Warning "清理演练库 $db 失败：$_"
            }
        }
    }
}
