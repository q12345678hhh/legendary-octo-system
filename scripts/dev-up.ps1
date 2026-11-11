<#
.SYNOPSIS
    启动本地开发拓扑（WP01-02）。

.DESCRIPTION
    1. 缺 .env 时由 .env.example 复制，绝不覆盖已有 .env
    2. 起栈前做端口占用检查：被非容器运行时进程占用时直接失败，并给出端口与进程
    3. 起栈后轮询健康状态，全部服务健康才返回成功
    4. Docker CLI 优先用 PATH 上的原生命令；没有时回退到 WSL 内的 Docker Engine

.PARAMETER Reset
    重建开发数据：删除命名卷后重新起栈，数据不可恢复

.PARAMETER Down
    停栈并删除容器，保留数据卷

.PARAMETER TimeoutSeconds
    等待服务健康的超时秒数，默认 180

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1 -Reset
#>
[CmdletBinding()]
param(
    [switch]$Reset,
    [switch]$Down,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeDir = Join-Path $repoRoot 'deploy\compose'
$composeFile = 'compose.dev.yaml'
$envFile = Join-Path $composeDir '.env'

$requiredServices = @('postgres', 'redis', 'minio', 'mailpit', 'otel-collector')
# compose 里 extra_hosts 未用到，但 minio-init 是一次性任务，不纳入健康等待
$portDefaults = [ordered]@{
    POSTGRES_PORT       = 5432
    REDIS_PORT          = 6379
    MINIO_API_PORT      = 9000
    MINIO_CONSOLE_PORT  = 9001
    SMTP_PORT           = 1025
    MAILPIT_UI_PORT     = 8025
    OTLP_GRPC_PORT      = 4317
    OTLP_HTTP_PORT      = 4318
}
# 端口被这些进程占着，说明是容器运行时（WSL2 端口转发或 Docker Desktop）
$containerRuntimePattern = '(?i)^(docker|com\.docker|vpnkit|wslrelay|wslhost|wslservice|vmmem|vmmemwsl)'

function ConvertTo-WslPath {
    param([string]$WindowsPath)
    $full = (Resolve-Path $WindowsPath).Path
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $rest = $full.Substring(2).Replace('\', '/')
    return "/mnt/$drive$rest"
}

function Read-EnvFile {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^#]*)') {
            $map[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $map
}

function Resolve-Docker {
    # 只做探测：原生命令的 stderr（含 WSL 代理告警）不应中断脚本
    $ErrorActionPreference = 'Continue'
    $native = Get-Command docker -ErrorAction SilentlyContinue
    if ($native) {
        & docker info 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return [pscustomobject]@{ Mode = 'native'; Distro = $null }
        }
        Write-Host 'Docker Desktop 未运行，回退到 WSL 内的 Docker Engine'
    }
    $wsl = Get-Command wsl -ErrorAction SilentlyContinue
    if ($wsl) {
        $distro = if ($env:PLATFORM_WSL_DISTRO) { $env:PLATFORM_WSL_DISTRO } else { 'pglab' }
        & wsl -d $distro -u root -- true 2>$null
        if ($LASTEXITCODE -eq 0) {
            return [pscustomobject]@{ Mode = 'wsl'; Distro = $distro }
        }
    }
    throw '找不到可用的 docker：请启动 Docker Desktop，或在 WSL 发行版内启动 Docker Engine（deploy/wsl/dockup.sh）'
}

$script:docker = $null

function Invoke-ComposeText {
    param([string[]]$Arguments)
    # 原生命令的 stderr 在 ErrorActionPreference=Stop 下会被当成终止错误，这里改为自行判定退出码
    $ErrorActionPreference = 'Continue'
    if ($script:docker.Mode -eq 'native') {
        Push-Location $composeDir
        try {
            $out = & docker compose -f $composeFile @Arguments 2>&1
        } finally {
            Pop-Location
        }
    } else {
        $wslDir = ConvertTo-WslPath $composeDir
        # 进入 bash 前要给含空格或花括号的参数补引号，否则会被 bash 拆开
        $argLine = ($Arguments | ForEach-Object {
                if ($_ -match '[\s|{}]') { "'$_'" } else { $_ }
            }) -join ' '
        $out = & wsl -d $script:docker.Distro -u root -- bash -lc "cd '$wslDir' && docker compose -f $composeFile $argLine" 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        throw ("docker compose {0} 失败（exit={1}）：`n{2}" -f ($Arguments -join ' '), $LASTEXITCODE, ($out -join "`n"))
    }
    return ($out -join "`n")
}

function Get-PortOwnerName {
    param([int]$Port)
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
    if (-not $connection) { return $null }
    $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
    if ($process) { return $process.ProcessName }
    return "pid=$($connection.OwningProcess)"
}

function Initialize-WslEngine {
    $ErrorActionPreference = 'Continue'
    & wsl -d $script:docker.Distro -u root -- bash -lc 'docker info >/dev/null 2>&1' 2>$null
    if ($LASTEXITCODE -eq 0) { return }
    $dockup = ConvertTo-WslPath (Join-Path $repoRoot 'deploy\wsl\dockup.sh')
    Write-Host "WSL($($script:docker.Distro)) 内 Docker Engine 未运行，调用 dockup.sh 启动"
    & wsl -d $script:docker.Distro -u root -- sh $dockup start
    if ($LASTEXITCODE -ne 0) { throw 'dockup.sh 启动 Docker Engine 失败，请查看 /var/log/dockerd.log' }
}

# 1. 环境变量模板
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $composeDir '.env.example') $envFile
    Write-Host '已从 .env.example 创建 .env（口令只对本地拓扑有效）'
}

$envMap = Read-EnvFile $envFile
$ports = [ordered]@{}
foreach ($key in $portDefaults.Keys) {
    $value = $portDefaults[$key]
    if ($envMap.ContainsKey($key) -and $envMap[$key]) { $value = [int]$envMap[$key] }
    $ports[$key] = $value
}

# 2. Docker 运行时
$script:docker = Resolve-Docker
if ($script:docker.Mode -eq 'wsl') {
    Initialize-WslEngine
}

# 3. 端口检查（容器运行时自己占用的端口视为正常）
$conflicts = @()
foreach ($key in $ports.Keys) {
    $port = $ports[$key]
    $owner = $null
    try {
        $owner = Get-PortOwnerName $port
    } catch {
        Write-Warning "无法检查端口 $port（$key）：$_"
    }
    if ($owner -and $owner -notmatch $containerRuntimePattern) {
        $conflicts += ("{0}={1} 被 {2} 占用" -f $key, $port, $owner)
    }
}
if ($conflicts.Count -gt 0) {
    throw ("端口冲突，请修改 deploy\compose\.env 里的端口映射后重试：`n  " + ($conflicts -join "`n  "))
}

# 4. 停栈 / 重建
$startedAt = Get-Date
if ($Down) {
    Invoke-ComposeText @('down') | Out-Null
    Write-Host '已停栈（数据卷保留）'
    return
}
if ($Reset) {
    Write-Host '重建开发数据：删除命名卷 pg_data / redis_data / minio_data'
    Invoke-ComposeText @('down', '--volumes') | Out-Null
}

# 5. 起栈并等待健康
Invoke-ComposeText @('up', '-d') | Out-Null
Write-Host ("已提交起栈，等待 {0} 个服务健康（上限 {1}s）" -f $requiredServices.Count, $TimeoutSeconds)

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$pending = @()
do {
    $pending = @()
    $output = Invoke-ComposeText @('ps', '--format', '{{.Service}}|{{.State}}|{{.Health}}')
    $status = @{}
    foreach ($line in ($output -split "`r?`n")) {
        if ($line -notmatch '\|') { continue }
        $parts = $line.Split('|')
        $health = ''
        if ($parts.Length -gt 2) { $health = $parts[2] }
        $status[$parts[0]] = [pscustomobject]@{ State = $parts[1]; Health = $health }
    }
    foreach ($service in $requiredServices) {
        if (-not $status.ContainsKey($service)) { $pending += "$service(未启动)"; continue }
        $current = $status[$service]
        if ($current.State -ne 'running') { $pending += "$service($($current.State))"; continue }
        if ($current.Health -and $current.Health -ne 'healthy') { $pending += "$service($($current.Health))" }
    }
    if ($pending.Count -eq 0) { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)

if ($pending.Count -gt 0) {
    throw ("等待服务健康超时（{0}s）：{1}。排查：docker compose -f {2} logs <服务名>" -f `
            $TimeoutSeconds, ($pending -join ', '), $composeFile)
}

$elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
Write-Host ''
Write-Host ("本地开发拓扑已就绪（{0}s）：" -f $elapsed)
Write-Host ("  PostgreSQL {0}  库 platform  owner=platform_owner  app=platform_app" -f $ports['POSTGRES_PORT'])
Write-Host ("  Redis      {0}" -f $ports['REDIS_PORT'])
Write-Host ("  MinIO      {0}  Console {1}" -f $ports['MINIO_API_PORT'], $ports['MINIO_CONSOLE_PORT'])
Write-Host ("  Mailpit    SMTP {0}  界面 http://localhost:{1}" -f $ports['SMTP_PORT'], $ports['MAILPIT_UI_PORT'])
Write-Host ("  OTLP       grpc {0}  http {1}" -f $ports['OTLP_GRPC_PORT'], $ports['OTLP_HTTP_PORT'])
Write-Host ''
Write-Host 'API 与 Worker 不在本脚本内启动：VS Code 调试或 java -jar 均可。'
if ($script:docker.Mode -eq 'wsl') {
    Write-Host '提示：WSL 内的 Docker Engine 会随发行版空闲关停，届时重跑本脚本即可。'
}
