<#
.SYNOPSIS
    SRTP 版本回滚 —— 把服务切回上一个（或指定）版本。

.DESCRIPTION
    从 C:\srtp\releases 中选取目标版本，切换 current junction 并重启服务，
    健康检查通过后更新 current.txt / previous.txt。
    回滚后 previous 会指向「回滚前的版本」，因此可以再次 rollback 回到新版本。

.PARAMETER Target
    previous     回滚到上一个版本（默认）
    <version>    回滚到指定版本号，例如 1.0.0-41-9f8e7d6

.PARAMETER ListOnly
    只列出可回滚的版本，不做任何改动。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\rollback.ps1 -Target previous
    powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\rollback.ps1 -Target 1.0.0-41-9f8e7d6
#>
[CmdletBinding()]
param(
    [string]$Target = 'previous',
    [int]$AppPort = 8090,
    [switch]$ListOnly
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Common.ps1"

$startedAt = Get-Date
$paths = Get-SrtpPaths

$releases = Get-ReleaseVersions
$current = Get-CurrentVersion
$previous = Get-PreviousVersion

if ($ListOnly) {
    Write-Host ''
    Write-Host '可选回滚版本（新 -> 旧）:' -ForegroundColor Cyan
    foreach ($r in $releases) {
        $mark = ''
        if ($r -eq $current) { $mark = '  <== 当前版本' }
        elseif ($r -eq $previous) { $mark = '  <== 上一个版本' }
        Write-Host ("  {0}{1}" -f $r, $mark)
    }
    Write-Host ''
    exit 0
}

Write-Log "======== 回滚开始（目标: $Target）========"
Assert-Administrator

# ---------- 解析目标版本 ----------
if ($Target -eq 'previous') {
    if ([string]::IsNullOrWhiteSpace($previous)) {
        throw '没有记录到上一个版本（previous.txt 为空），无法回滚。可用 -Target <版本号> 指定。'
    }
    $newVersion = $previous
} else {
    $newVersion = $Target
}

if (-not (Test-Path (Join-Path $paths.Releases $newVersion))) {
    Write-Log "目标版本不存在: $newVersion" -Level ERROR
    Write-Log "现有的版本: $($releases -join ', ')" -Level ERROR
    throw "回滚失败：找不到版本 $newVersion"
}

if ($newVersion -eq $current) {
    Write-Log "目标版本与当前版本相同（$newVersion），无需回滚。" -Level WARN
    Write-Host '===SRTP_ROLLBACK_JSON_BEGIN==='
    Write-Host (@{ ok = $true; changed = $false; version = $current; message = 'already on target version' } | ConvertTo-Json -Compress)
    Write-Host '===SRTP_ROLLBACK_JSON_END==='
    exit 0
}

# ---------- 执行切换 ----------
Write-Step "停止服务（当前: $current）"
Stop-SrtpService | Out-Null

Write-Step "切换 current -> $newVersion"
Set-CurrentJunction -Version $newVersion

Write-Step '启动服务'
Invoke-ServiceCommand -Action start | Out-Null

Write-Step "健康检查（最多 90 秒）"
if (-not (Wait-SrtpHealthy -Port $AppPort -TimeoutSec 90)) {
    Write-Log "回滚到 $newVersion 后健康检查未通过！" -Level ERROR
    if ($current -and $current -ne $newVersion) {
        Write-Log "尝试切回原版本 $current" -Level WARN
        Stop-SrtpService | Out-Null
        Set-CurrentJunction -Version $current
        Invoke-ServiceCommand -Action start | Out-Null
        if (Wait-SrtpHealthy -Port $AppPort -TimeoutSec 90) {
            Write-Log "已切回 $current，服务恢复正常" -Level OK
        } else {
            Write-Log '切回后依然不健康，需要人工介入！' -Level ERROR
        }
    }
    throw '回滚失败：目标版本未通过健康检查。'
}

# ---------- 记录版本 ----------
Set-CurrentVersion -Version $newVersion -PreviousVersion $current

$duration = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
Write-Log "======== 回滚成功: $current -> $newVersion（用时 ${duration}s）========" -Level OK

$info = Get-ReleaseInfo -Version $newVersion
$summary = [ordered]@{
    ok            = $true
    changed       = $true
    from_version  = $current
    version       = $newVersion
    commit        = $info.commit
    healthy       = $true
    service_state = (Get-ServiceState)
    duration_sec  = $duration
    finished_at   = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
}
Write-Host '===SRTP_ROLLBACK_JSON_BEGIN==='
Write-Host ($summary | ConvertTo-Json -Depth 4 -Compress)
Write-Host '===SRTP_ROLLBACK_JSON_END==='
