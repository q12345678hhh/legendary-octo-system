<#
.SYNOPSIS
    WP01-05 遗留项收口的离线取证：运行时采集接入、真实 ZIP 安装器、多状态托盘图标。

.DESCRIPTION
    纯离线，不需要数据库或后端服务：

    1. desktop-agent 解决方案 Release 构建（警告即错误）。
    2. Device.Checks：采集适配器（假探针）、ZIP 安装器（覆盖/回滚/目录穿越）等离线断言。
    3. PrivacyEngine.Checks：排除判定离线断言。
    4. 托盘壳 --self-check：四种状态的菜单、提示与图标颜色。
    5. PrivacyEngine.Checks --live：真实桌面窗口采样，验证线上探针路径。

    产物写入 docs/evidence/wp01-05-followup/，任一步失败即抛错退出。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\work-assistant-followup-verify.ps1
#>
[CmdletBinding()]
param(
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $repoRoot 'desktop-agent'
$evidence = if ($EvidenceDir) { $EvidenceDir } else { Join-Path $repoRoot 'docs\evidence\wp01-05-followup' }
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

$ownedEvidence = @(
    'build.txt', 'offline-checks.txt', 'privacy-checks.txt', 'indicator-icons.txt', 'live-window-probe.txt'
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

function Invoke-Captured {
    param([string]$Name, [scriptblock]$Command)
    & $Command 2>&1 | Out-File -FilePath (Join-Path $evidence $Name) -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        Get-Content (Join-Path $evidence $Name) | Select-Object -Last 40 | Write-Host
        throw "$Name 失败：exit=$LASTEXITCODE"
    }

    Get-Content (Join-Path $evidence $Name) | Select-Object -Last 5 | Write-Host
}

Write-Step '桌面端 Release 构建（警告即错误）'
& dotnet build (Join-Path $desktop 'desktop-agent.sln') -c Release --nologo 2>&1 |
    Out-File -FilePath (Join-Path $evidence 'build.txt') -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw "构建失败：exit=$LASTEXITCODE" }
Get-Content (Join-Path $evidence 'build.txt') | Select-Object -Last 4 | Write-Host

Write-Step '设备离线自检'
Invoke-Captured 'offline-checks.txt' {
    dotnet run --project (Join-Path $desktop 'device\WorkAssistant.Device.Checks') -c Release --no-build
}

Write-Step '隐私判定离线自检'
Invoke-Captured 'privacy-checks.txt' {
    dotnet run --project (Join-Path $desktop 'privacy-engine\WorkAssistant.PrivacyEngine.Checks') -c Release --no-build
}

Write-Step '托盘图标与菜单自检'
$report = Join-Path $env:TEMP ("wa-followup-" + [guid]::NewGuid().ToString('N') + '.txt')
$appExe = Join-Path $desktop 'windows-app\WorkAssistant.WindowsApp\bin\Release\net8.0-windows\WorkAssistant.WindowsApp.exe'
$self = Start-Process -FilePath $appExe -ArgumentList '--self-check', '--report', $report -Wait -PassThru
if ($self.ExitCode -ne 0) { throw "托盘自检失败：exit=$($self.ExitCode)" }
Copy-Item $report (Join-Path $evidence 'indicator-icons.txt') -Force
Remove-Item $report -Force
Get-Content (Join-Path $evidence 'indicator-icons.txt') | Select-Object -Last 3 | Write-Host

Write-Step '真实桌面窗口采样'
Invoke-Captured 'live-window-probe.txt' {
    dotnet run --project (Join-Path $desktop 'privacy-engine\WorkAssistant.PrivacyEngine.Checks') -c Release --no-build -- --live
}

Write-Host ''
Write-Host "证据已写入 $evidence"
