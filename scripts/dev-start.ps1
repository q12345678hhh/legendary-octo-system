<#
.SYNOPSIS
    本机一键启动：WSL 依赖 + 后端 API + 前端 Web。

.DESCRIPTION
    适用于不使用 Docker Desktop、依赖 WSL(pglab) 内 Docker Engine 与 PostgreSQL 的本机环境。
    1. 确保 WSL 内的 PostgreSQL 集群在线
    2. 确保 WSL 内的 Docker Engine 在线，Redis / MinIO 等容器随引擎自动恢复
    3. 后端 8080、前端 5173 未运行时以后台进程启动，日志写入 %LOCALAPPDATA%\platform-dev
    4. 等待健康检查通过后打印访问地址与演示账号

.PARAMETER Stop
    停止由本脚本启动的后端与前端（依赖容器保持运行）

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-start.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-start.ps1 -Stop
#>
[CmdletBinding()]
param(
    [switch]$Stop
)

# 原生命令（wsl / docker / mvn）的 stderr 在 PS5.1 下会被当成终止错误，
# 这里统一用退出码判断，避免 WSL 的 localhost 代理提示中断脚本。
$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$distro = if ($env:PLATFORM_WSL_DISTRO) { $env:PLATFORM_WSL_DISTRO } else { 'pglab' }
$runDir = Join-Path $env:LOCALAPPDATA 'platform-dev'
$backendPidFile = Join-Path $runDir 'backend.pid'
$webPidFile = Join-Path $runDir 'web.pid'
$backendLog = Join-Path $runDir 'backend.log'
$backendErr = Join-Path $runDir 'backend.err.log'
$webLog = Join-Path $runDir 'web.log'
$webErr = Join-Path $runDir 'web.err.log'
$jar = Join-Path $repoRoot 'backend\boot-api\target\boot-api-0.1.0-SNAPSHOT.jar'
$pinnedJavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'

New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Write-Step([string]$Text) {
    Write-Host "==> $Text"
}

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port([int]$Port, [int]$Seconds, [string]$Name) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return }
        Start-Sleep -Seconds 2
    }
    throw "$Name 端口 $Port 在 $Seconds 秒内未就绪"
}

function Wait-Health([string]$Url, [int]$Seconds, [string]$Name, [string]$LogFile) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    $last = '尚未响应'
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
            $last = "HTTP $($response.StatusCode)"
        } catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    $tail = ''
    if (Test-Path $LogFile) {
        $tail = (Get-Content $LogFile -Tail 20 -ErrorAction SilentlyContinue) -join "`n"
    }
    throw "$Name 在 $Seconds 秒内未就绪（$last）。日志：$LogFile`n$tail"
}

function Stop-Tracked([string]$PidFile, [string]$Name) {
    if (-not (Test-Path $PidFile)) {
        Write-Host "  $Name 未由本脚本启动，跳过"
        return
    }
    $processId = [int](Get-Content $PidFile -Raw)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        # pnpm 会再派生 vite，必须按进程树终止，否则端口仍被占用
        & taskkill /PID $processId /T /F 2>&1 | Out-Null
        Write-Host "  已停止 $Name（pid=$processId）"
    } else {
        Write-Host "  $Name 进程已不存在（pid=$processId）"
    }
    Remove-Item $PidFile -Force
}

function ConvertTo-WslPath([string]$WindowsPath) {
    $full = (Resolve-Path $WindowsPath).Path
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $rest = $full.Substring(2).Replace('\', '/')
    return "/mnt/$drive$rest"
}

if ($Stop) {
    Write-Step '停止后端与前端'
    Stop-Tracked $webPidFile '前端'
    Stop-Tracked $backendPidFile '后端'
    Write-Host '依赖容器与数据库保持运行；需要停栈执行 scripts\dev-up.ps1 -Down。'
    return
}

# 1. WSL 与 PostgreSQL
Write-Step "检查 WSL($distro) 与 PostgreSQL"
& wsl -d $distro -u root -- true 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "无法进入 WSL 发行版 $distro，请先启动它（wsl -d $distro）"
}
& wsl -d $distro -u root -- bash -lc 'if ! pg_isready -q; then v=$(pg_lsclusters -h | awk "NR==1{print \$1}"); c=$(pg_lsclusters -h | awk "NR==1{print \$2}"); pg_ctlcluster "$v" "$c" start; fi; pg_isready' 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'PostgreSQL 启动失败，请检查 WSL 内 pg_lsclusters 输出'
}
Wait-Port 5432 30 'PostgreSQL'
Write-Host '  PostgreSQL 5432 就绪'

# 2. Docker Engine 与依赖容器（Redis / MinIO 等为 unless-stopped，会随引擎恢复）
Write-Step '检查 WSL 内 Docker Engine'
& wsl -d $distro -u root -- bash -lc 'docker info >/dev/null 2>&1' 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    $dockup = ConvertTo-WslPath (Join-Path $repoRoot 'deploy\wsl\dockup.sh')
    & wsl -d $distro -u root -- sh $dockup start 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'WSL 内 Docker Engine 启动失败，请查看 /var/log/dockerd.log'
    }
}
foreach ($port in 6379, 9000) {
    try {
        Wait-Port $port 60 '依赖容器'
    } catch {
        throw "依赖端口 $port 未就绪。请执行 scripts\dev-up.ps1 重建开发栈"
    }
}
Write-Host '  Redis 6379、MinIO 9000 就绪'

# 3. 后端
Write-Step '检查后端 API'
if (Test-Port 8080) {
    Write-Host '  8080 已在监听，跳过后端启动'
} else {
    if (-not (Test-Path $jar)) {
        $javaHome = if (Test-Path $pinnedJavaHome) { $pinnedJavaHome } else { $env:JAVA_HOME }
        if (-not $javaHome) { throw "缺少后端 jar 且未找到 JDK 21：$jar" }
        Write-Host '  缺少后端 jar，先构建（首次较慢）...'
        & mvn -B -ntp -f (Join-Path $repoRoot 'backend\pom.xml') -pl boot-api -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
    }
    $java = if (Test-Path $pinnedJavaHome) {
        Join-Path $pinnedJavaHome 'bin\java.exe'
    } else {
        (Get-Command java -ErrorAction Stop).Source
    }
    $backend = Start-Process -FilePath $java `
        -ArgumentList '-jar', $jar `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $backendLog `
        -RedirectStandardError $backendErr `
        -WindowStyle Hidden -PassThru
    Set-Content -Path $backendPidFile -Value $backend.Id
    Write-Host "  已启动后端（pid=$($backend.Id)），日志 $backendLog"
    Wait-Health 'http://localhost:8080/actuator/health' 150 '后端 API' $backendLog
}
Write-Host '  后端 API 就绪：http://localhost:8080/actuator/health'

# 4. 前端
Write-Step '检查前端 Web'
if (Test-Port 5173) {
    Write-Host '  5173 已在监听，跳过前端启动'
} else {
    $pnpm = (Get-Command pnpm.cmd -ErrorAction SilentlyContinue)
    if (-not $pnpm) {
        $pnpm = Get-Command pnpm -ErrorAction Stop
    }
    $web = Start-Process -FilePath $pnpm.Source `
        -ArgumentList '--dir', 'frontend/apps/web', 'run', 'dev', '--host', '127.0.0.1', '--port', '5173' `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $webLog `
        -RedirectStandardError $webErr `
        -WindowStyle Hidden -PassThru
    Set-Content -Path $webPidFile -Value $web.Id
    Write-Host "  已启动前端（pid=$($web.Id)），日志 $webLog"
    Wait-Health 'http://127.0.0.1:5173/' 90 '前端 Web' $webLog
}

Write-Host ''
Write-Host '本地环境已就绪：'
Write-Host '  访问      http://127.0.0.1:5173'
Write-Host '  租户 ID   aaaaaaaa-0000-0000-0000-000000000001'
Write-Host '  邮箱      demo.manager@example.test'
Write-Host '  密码      Demo#Passw0rd2026'
Write-Host "  日志      $runDir"
Write-Host '  停止应用  powershell -ExecutionPolicy Bypass -File scripts\dev-start.ps1 -Stop'
