<#
.SYNOPSIS
    SRTP 服务器状态巡检 —— 输出结构化 JSON 供 GitHub Actions 汇总展示。

.DESCRIPTION
    采集内容：服务状态、端口监听、健康检查、当前/历史版本、进程内存、
    MySQL 状态、磁盘与内存余量、最近一次部署信息、密钥文件是否就位（只看键名，不输出值）。
    不修改任何配置。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\status.ps1
#>
[CmdletBinding()]
param(
    [int]$AppPort = 8090,
    [int]$ReleaseHistory = 10,
    [int]$LogTailLines = 8,
    [switch]$FailIfDown
)

$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib\Common.ps1"

$paths = Get-SrtpPaths
$status = [ordered]@{}

# ---------- 主机信息 ----------
try {
    $os = Get-CimInstance Win32_OperatingSystem
    $status.host = $env:COMPUTERNAME
    $status.os = $os.Caption
    $status.uptime_hours = [math]::Round(((Get-Date) - $os.LastBootUpTime).TotalHours, 1)
    $status.memory_total_mb = [math]::Round($os.TotalVisibleMemorySize / 1KB, 0)
    $status.memory_free_mb = [math]::Round($os.FreePhysicalMemory / 1KB, 0)
} catch {
    $status.host = $env:COMPUTERNAME
    $status.os = 'unknown'
}

# ---------- 版本 ----------
$current = Get-CurrentVersion
$previous = Get-PreviousVersion
$status.current_version = $current
$status.previous_version = $previous

$releases = Get-ReleaseVersions
$status.release_count = $releases.Count
$status.recent_releases = @($releases | Select-Object -First $ReleaseHistory)

if ($current) {
    $info = Get-ReleaseInfo -Version $current
    if ($null -ne $info) {
        $status.current_commit = $info.commit
        $status.current_branch = $info.branch
        $status.deployed_at = $info.deployed_at
        $status.current_jar_sha256 = $info.jar_sha256
    }
    $status.current_path = Join-Path $paths.Current 'app'
}

# ---------- 服务与进程 ----------
$status.service_state = Get-ServiceState
try {
    $svc = Get-CimInstance Win32_Service -Filter "Name='srtp-server'" -ErrorAction Stop
    if ($null -ne $svc) {
        $status.service_start_mode = $svc.StartMode
        $status.service_pid = $svc.ProcessId
        if ($svc.ProcessId -gt 0) {
            $p = Get-Process -Id $svc.ProcessId -ErrorAction SilentlyContinue
            if ($null -ne $p) {
                $status.process_working_set_mb = [math]::Round($p.WorkingSet64 / 1MB, 0)
                $status.process_private_mb = [math]::Round($p.PrivateMemorySize64 / 1MB, 0)
                $status.process_started = $p.StartTime.ToString('yyyy-MM-dd HH:mm:ss')
            }
        }
    }
} catch { }

# ---------- 端口与健康 ----------
$status.port = $AppPort
$status.port_listening = Test-PortOpen -Port $AppPort -TimeoutMs 2500
$status.health = 'unreachable'
$status.http_root = 0
if ($status.port_listening) {
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -TimeoutSec 6 -Uri ("http://127.0.0.1:{0}/actuator/health" -f $AppPort)
        $status.http_health = $resp.StatusCode
        try {
            $status.health = ($resp.Content | ConvertFrom-Json).status
        } catch {
            $status.health = $resp.Content
        }
    } catch {
        $status.health = 'error: ' + $_.Exception.Message
    }
    try {
        $root = Invoke-WebRequest -UseBasicParsing -TimeoutSec 6 -Uri ("http://127.0.0.1:{0}/" -f $AppPort)
        $status.http_root = $root.StatusCode
    } catch { }
}
$status.healthy = ($status.health -eq 'UP')

# ---------- MySQL ----------
$mysql = Get-MysqlState
$status.mysql_service = $mysql.Name
$status.mysql_state = $mysql.Status
$status.mysql_listening = Test-PortOpen -Port 3306 -TimeoutMs 1500

# ---------- 磁盘 ----------
try {
    $drive = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    $status.disk_c_total_gb = [math]::Round($drive.Size / 1GB, 1)
    $status.disk_c_free_gb = [math]::Round($drive.FreeSpace / 1GB, 1)
} catch { }

# ---------- 密钥文件（只报告键名与时间，绝不输出值） ----------
if (Test-Path $paths.EnvFile) {
    $map = Read-EnvFile -Path $paths.EnvFile
    $status.env_file_present = $true
    $status.env_keys = @($map.Keys)
    $status.env_updated_at = (Get-Item $paths.EnvFile).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
    # 只报告是否已设置，不泄露内容
    $status.env_db_password_set = -not [string]::IsNullOrWhiteSpace($map['DB_PASSWORD'])
    $status.env_api_key_set = (-not [string]::IsNullOrWhiteSpace($map['APP_API_KEY']))
} else {
    $status.env_file_present = $false
}

# ---------- 日志尾部 ----------
if (Test-Path $paths.DeployLog) {
    $status.recent_deploy_log = @(Get-Content -Path $paths.DeployLog -Tail $LogTailLines -Encoding UTF8)
}
$appOut = Join-Path $paths.Logs 'srtp-server.out.log'
if (Test-Path $appOut) {
    $status.recent_app_log = @(Get-Content -Path $appOut -Tail $LogTailLines -Encoding UTF8)
}

# ---------- 输出 ----------
$status.checked_at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

if ($status.env_file_present) {
    $envDesc = "已就位（DB_PASSWORD={0}, APP_API_KEY={1}）" -f $status.env_db_password_set, $status.env_api_key_set
} else {
    $envDesc = '缺失'
}

Write-Host ''
Write-Host '================ SRTP 服务器状态 ================' -ForegroundColor Cyan
Write-Host ("主机        : {0}" -f $status.host)
Write-Host ("服务状态    : {0} (启动方式 {1})" -f $status.service_state, $status.service_start_mode)
Write-Host ("当前版本    : {0}" -f $status.current_version)
Write-Host ("上一版本    : {0}" -f $status.previous_version)
Write-Host ("健康检查    : {0} (HTTP /={1}, /actuator/health={2})" -f $status.health, $status.http_root, $status.http_health)
Write-Host ("MySQL       : {0} / {1}" -f $status.mysql_service, $status.mysql_state)
Write-Host ("内存        : 空闲 {0} MB / 共 {1} MB" -f $status.memory_free_mb, $status.memory_total_mb)
Write-Host ("C 盘        : 空闲 {0} GB / 共 {1} GB" -f $status.disk_c_free_gb, $status.disk_c_total_gb)
Write-Host ("密钥文件    : {0}" -f $envDesc)
Write-Host '=================================================' -ForegroundColor Cyan
Write-Host ''

Write-Host '===SRTP_STATUS_JSON_BEGIN==='
Write-Host ($status | ConvertTo-Json -Depth 5 -Compress)
Write-Host '===SRTP_STATUS_JSON_END==='

if ($FailIfDown -and -not $status.healthy) {
    exit 1
}
exit 0
