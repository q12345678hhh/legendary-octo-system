<#
.SYNOPSIS
    回退本次 Docker 排障与验证脚本适配带来的改动。

.DESCRIPTION
    默认只回退环境侧改动（可重复执行）：
      1. 停止并删除计划任务 platform-pglab-dockerd；
      2. 把 nano.exe 的入站规则恢复为 RemoteAddress=Any、边缘穿越=DeferToUser；
      3. 可选 -RevertCode：用 git checkout 还原 5 个被修改的脚本/代码文件；
      4. 可选 -RestorePgPort：把 pglab 内 postgres 的端口改回 5432（5432 可用时才生效）；
      5. 可选 -CleanupVerifyData：删除验证脚本插入的会话与验证账号数据。

    防火墙回退需要管理员权限；-RevertCode 会丢弃这些文件上的本地修改。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\rollback-verify-env.ps1 -DryRun

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\rollback-verify-env.ps1 -RevertCode -CleanupVerifyData
#>
param(
    [switch]$RevertCode,
    [switch]$RestorePgPort,
    [switch]$CleanupVerifyData,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$env:WSL_UTF8 = '1'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$distro = if ($env:PLATFORM_WSL_DISTRO) { $env:PLATFORM_WSL_DISTRO } else { 'pglab' }
$pgContainer = if ($env:PLATFORM_PG_CONTAINER) { $env:PLATFORM_PG_CONTAINER } else { 'platform-pg-dev' }
$taskName = 'platform-pglab-dockerd'

function Invoke-Step {
    param([string]$Description, [scriptblock]$Action)
    if ($DryRun) {
        Write-Host "[dry-run] $Description"
        return
    }
    Write-Host "[执行] $Description"
    & $Action
}

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function ConvertTo-WslPath {
    param([string]$Path)
    $full = (Resolve-Path -LiteralPath $Path).Path
    $drive = $full.Substring(0, 1).ToLower()
    return '/mnt/' + $drive + ($full.Substring(2) -replace '\\', '/')
}

# 1. 计划任务
$task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($task) {
    Invoke-Step "停止并删除计划任务 $taskName" {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    }
} else {
    Write-Host "[跳过] 计划任务 $taskName 不存在"
}

# 2. 防火墙：nano.exe 入站规则恢复为默认放行口径
$nanoRules = @(Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue |
        Where-Object { $_.Program -like '*nano.exe' } |
        ForEach-Object { $_ | Get-NetFirewallRule } |
        Where-Object { $_.Direction -eq 'Inbound' })

if ($nanoRules.Count -eq 0) {
    Write-Host '[跳过] 未找到 nano.exe 的入站规则'
} elseif (-not (Test-Administrator) -and -not $DryRun) {
    Write-Warning '防火墙规则回退需要管理员权限，本次跳过。请以管理员身份重新运行。'
} else {
    foreach ($rule in $nanoRules) {
        Invoke-Step "恢复防火墙规则 $($rule.Name) 为 RemoteAddress=Any / DeferToUser" {
            Set-NetFirewallRule -Name $rule.Name -RemoteAddress Any -EdgeTraversalPolicy DeferToUser
        }
    }
}

# 3. 代码回退（可选）
if ($RevertCode) {
    $files = @(
        'deploy/wsl/dockup.sh',
        'scripts/db-verify.ps1',
        'scripts/platform-skeleton-verify.ps1',
        'scripts/work-assistant-verify.ps1',
        'desktop-agent/device/WorkAssistant.Device/DeviceApiClient.cs'
    )
    Invoke-Step 'git checkout 还原 5 个脚本/代码文件' {
        & git -C $repoRoot checkout -- $files
        if ($LASTEXITCODE -ne 0) { throw "git checkout 失败（退出码 $LASTEXITCODE）" }
    }
} else {
    Write-Host '[跳过] 代码回退（需要时加 -RevertCode）'
}

# 4. postgres 端口改回 5432（可选）
if ($RestorePgPort) {
    $composeDir = Join-Path $repoRoot 'deploy\compose'
    $wslCompose = ConvertTo-WslPath $composeDir
    Write-Warning '5432 若仍被 Docker Desktop 占用，重建会失败；失败时保持 55433 不变。'
    Invoke-Step '在 pglab 内以 POSTGRES_PORT=5432 重建 postgres' {
        & wsl -d $distro -u root -- sh -c "cd '$wslCompose' && POSTGRES_PORT=5432 docker compose up -d --no-deps --force-recreate postgres"
        if ($LASTEXITCODE -ne 0) { throw "重建 postgres 失败（退出码 $LASTEXITCODE）" }
    }
} else {
    Write-Host '[跳过] PostgreSQL 端口回退（需要时加 -RestorePgPort）'
}

# 5. 清理验证数据（可选）
if ($CleanupVerifyData) {
    $verifyUserIds = @(
        'e0000000-0000-4000-8000-0000000000a1', 'e0000000-0000-4000-8000-0000000000b1',
        'e0000000-0000-4000-8000-0000000000c1', 'f0000000-0000-4000-8000-0000000000a1'
    )
    $verifyActorIds = @(
        'e0000000-0000-4000-8000-0000000000a2', 'e0000000-0000-4000-8000-0000000000b2',
        'e0000000-0000-4000-8000-0000000000c2', 'f0000000-0000-4000-8000-0000000000a2'
    )
    $quietUserIds = ($verifyUserIds | ForEach-Object { "'$_'" }) -join ','
    $quietActorIds = ($verifyActorIds | ForEach-Object { "'$_'" }) -join ','

    $tenants = @()
    try {
        $rawTenants = & wsl -d $distro -u root -- docker exec $pgContainer psql -U platform_owner -d platform -At `
            -c "SELECT id FROM platform_tenant_directory WHERE status = 'active'" 2>$null
        $tenants = @($rawTenants | Where-Object { $_ -match '^[0-9a-f-]{36}$' })
    } catch {
        Write-Warning "读取租户目录失败，跳过验证数据清理：$($_.Exception.Message)"
    }

    foreach ($tenant in $tenants) {
        $sql = "SELECT set_config('app.tenant_id', '$tenant', false);" +
        "DELETE FROM identity_session WHERE user_id IN ($quietUserIds);" +
        "DELETE FROM authorization_assignment WHERE actor_id IN ($quietActorIds);" +
        "DELETE FROM identity_tenant_member WHERE user_id IN ($quietUserIds);" +
        "DELETE FROM identity_actor WHERE id IN ($quietActorIds);" +
        "DELETE FROM identity_user_account WHERE id IN ($quietUserIds);"
        Invoke-Step "清理租户 $tenant 下的验证账号与会话" {
            try {
                & wsl -d $distro -u root -- docker exec $pgContainer psql -U platform_owner -d platform `
                    -v ON_ERROR_STOP=1 -c $sql | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "psql 退出码 $LASTEXITCODE" }
            } catch {
                Write-Warning "租户 $tenant 清理未完成（可能存在业务外键引用）：$($_.Exception.Message)"
            }
        }
    }
} else {
    Write-Host '[跳过] 验证数据清理（需要时加 -CleanupVerifyData）'
}

Write-Host ''
if ($DryRun) {
    Write-Host '演练完成，未做任何修改。去掉 -DryRun 后按上述步骤执行。'
} else {
    Write-Host '回退完成。如需确认：Get-ScheduledTask -TaskName platform-pglab-dockerd'
}
