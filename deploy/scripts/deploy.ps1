<#
.SYNOPSIS
    SRTP 部署脚本 —— 把 CI 上传的发布载荷切换为当前版本（promote）。

.DESCRIPTION
    在服务器端执行，流程：
      1. （可选）从上传的 env 文件更新 ACL 锁定的 C:\srtp\shared\.env
      2. 校验并安置新的发布载荷到 C:\srtp\releases\<Version>
      3. 由 .env 渲染服务定义（密钥以 <env> 注入服务进程）
      4. 停止服务 -> 切换 current junction -> 启动服务
      5. 健康检查；不通过则【自动回滚】到上一个版本并以非 0 退出
      6. 记录 current/previous 与 release.json

.PARAMETER Version
    发布版本号，例如 1.0.0-42-a1b2c3d

.PARAMETER PayloadDir
    CI 上传的载荷目录，默认 C:\srtp\incoming\<Version>。
    目录内需能（递归）找到 srtp-server.jar / python\algorithm_bridge.py / srtp-app-source\四算法整合优化.py

.PARAMETER EnvFile
    CI 上传的密钥文件（KEY=VALUE）。不传则沿用服务器上已有的 .env。

.PARAMETER EnvOnly
    只同步环境变量与重渲染服务定义并重启，不切换版本（用于改密钥后快速生效）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\deploy.ps1 `
        -Version 1.0.0-42-a1b2c3d -EnvFile C:\srtp\incoming\secrets.env
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$PayloadDir = '',
    [string]$EnvFile = '',
    [string]$Commit = '',
    [string]$Branch = '',
    [string]$RunUrl = '',
    [switch]$EnvOnly,
    [int]$AppPort = 8090
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Common.ps1"

$startedAt = Get-Date
$paths = Get-SrtpPaths

if (-not $PayloadDir) { $PayloadDir = Join-Path $paths.Incoming $Version }

Write-Log "======== 开始部署 $Version ========"
Assert-Administrator

# 关键文件使用的名字（算法脚本原名含中文，这里做兼容匹配）
$JAR_NAME = 'srtp-server.jar'
$BRIDGE_NAME = 'algorithm_bridge.py'

# ------------------------------------------------------------
# 1. 更新 .env（若 CI 下发了新密钥）
# ------------------------------------------------------------
if ($EnvFile -and (Test-Path $EnvFile)) {
    Write-Step '同步环境变量 / 密钥'
    $newMap = Read-EnvFile -Path $EnvFile

    # 以服务器上已有配置为底，避免 CI 未下发的键被清空
    $merged = [ordered]@{}
    foreach ($k in (Read-EnvFile -Path $paths.EnvFile).Keys) { $merged[$k] = (Read-EnvFile -Path $paths.EnvFile)[$k] }
    foreach ($k in $newMap.Keys) {
        if (-not [string]::IsNullOrWhiteSpace($newMap[$k])) { $merged[$k] = $newMap[$k] }
    }
    # 运行期必需的、与机器相关的键，始终由服务器自己决定
    if (-not $merged.Contains('DB_USERNAME')) { $merged['DB_USERNAME'] = 'root' }
    $merged['PYTHON_EXECUTABLE'] = Join-Path $paths.Python 'python.exe'
    $merged['PYTHON_SCRIPT_DIR'] = 'python'
    $merged['SERVER_PORT'] = "$AppPort"

    if ([string]::IsNullOrWhiteSpace($merged['DB_PASSWORD'])) {
        throw 'DB_PASSWORD 为空：请在 GitHub 仓库 Secrets 中配置 DB_PASSWORD 后重试。'
    }

    Save-EnvFile -Path $paths.EnvFile -Map $merged
    Protect-SecretFile -Path $paths.EnvFile
    Remove-Item $EnvFile -Force -ErrorAction SilentlyContinue
    Write-Log '密钥文件已更新并锁定 ACL' -Level OK
} else {
    Write-Step '沿用服务器上已有的 .env'
    if (-not (Test-Path $paths.EnvFile)) {
        throw "服务器上不存在 $($paths.EnvFile)，请先运行 bootstrap-server.ps1（或由 CI 下发 EnvFile）。"
    }
}

$envMap = Read-EnvFile -Path $paths.EnvFile
if ([string]::IsNullOrWhiteSpace($envMap['DB_PASSWORD'])) {
    throw 'DB_PASSWORD 为空，请检查 GitHub Secrets 与 C:\srtp\shared\.env。'
}

# ------------------------------------------------------------
# 2. 安置发布载荷
# ------------------------------------------------------------
$isRedeploy = $false
if (-not $EnvOnly) {
    Write-Step '安置发布载荷'
    if (-not (Test-Path $PayloadDir)) { throw "载荷目录不存在: $PayloadDir" }

    # 兼容 scp 可能产生的多余嵌套：递归定位包含 jar 的 app 目录
    $jarFile = Get-ChildItem -Path $PayloadDir -Filter $JAR_NAME -Recurse -File -ErrorAction SilentlyContinue |
               Select-Object -First 1
    if ($null -eq $jarFile) {
        $jarFile = Get-ChildItem -Path $PayloadDir -Filter 'srtp-server*.jar' -Recurse -File -ErrorAction SilentlyContinue |
                   Select-Object -First 1
    }
    if ($null -eq $jarFile) { throw "载荷中未找到 $JAR_NAME（目录: $PayloadDir）" }

    $appSrc = $jarFile.Directory.FullName
    Write-Log "载荷 app 目录: $appSrc"

    foreach ($required in @(
        (Join-Path $appSrc "python\$BRIDGE_NAME"),
        (Join-Path $appSrc 'srtp-app-source')
    )) {
        if (-not (Test-Path $required)) { throw "载荷缺少必需内容: $required" }
    }

    $targetRelease = Join-Path $paths.Releases $Version
    if (Test-Path $targetRelease) {
        Write-Log "目标版本目录已存在，执行覆盖式重部署: $targetRelease" -Level WARN
        $isRedeploy = $true
        Remove-Item $targetRelease -Recurse -Force
    }
    New-Item -ItemType Directory -Path $targetRelease -Force | Out-Null

    $appDest = Join-Path $targetRelease 'app'
    Copy-Item -Path $appSrc -Destination $appDest -Recurse -Force
    # 统一 jar 名字，便于服务参数固定
    $copiedJar = Get-ChildItem -Path $appDest -Filter 'srtp-server*.jar' -File | Select-Object -First 1
    if ($copiedJar.Name -ne $JAR_NAME) {
        Move-Item -Path $copiedJar.FullName -Destination (Join-Path $appDest $JAR_NAME) -Force
    }
    # 清理不需要随发布携带的东西
    Get-ChildItem -Path $appDest -Filter '__pycache__' -Recurse -Directory -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

    Write-Log "载荷已就位: $appDest" -Level OK

    # release.json
    $meta = [ordered]@{
        version      = $Version
        commit       = $Commit
        branch       = $Branch
        run_url      = $RunUrl
        deployed_at  = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
        deployed_by  = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        jar_sha256   = (Get-FileHash -Path (Join-Path $appDest $JAR_NAME) -Algorithm SHA256).Hash
        redeploy     = $isRedeploy
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $targetRelease 'release.json'),
        ($meta | ConvertTo-Json -Depth 4),
        (New-Object System.Text.UTF8Encoding($false))
    )
} else {
    Write-Step 'EnvOnly 模式：不切换版本'
}

# ------------------------------------------------------------
# 3. 渲染服务定义
# ------------------------------------------------------------
Write-Step '渲染服务定义（注入环境变量）'
New-ServiceXmlFromTemplate -EnvMap $envMap
Protect-SecretFile -Path $paths.ServiceXml

# ------------------------------------------------------------
# 4. 切换版本并重启服务
# ------------------------------------------------------------
$previous = Get-CurrentVersion
if ($EnvOnly) {
    $activeVersion = $previous
    $mode = 'env-only'
} else {
    $activeVersion = $Version
    $mode = 'full'
}
if ([string]::IsNullOrWhiteSpace($activeVersion)) {
    throw '当前没有可用的发布版本（current.txt 为空），请先执行一次正常部署。'
}
if (-not (Test-Path (Join-Path $paths.Releases $activeVersion))) {
    throw "目标版本目录不存在: $activeVersion"
}

Write-Step "停止服务（当前版本: $(if ($previous) { $previous } else { '无' })）"
Stop-SrtpService | Out-Null

Write-Step "切换 current -> $activeVersion"
Set-CurrentJunction -Version $activeVersion

$svcState = Get-ServiceState
if ($svcState -eq 'NotInstalled') {
    Write-Step '注册 Windows 服务'
    Invoke-ServiceCommand -Action install | Out-Null
    Start-Sleep -Seconds 2
}

Write-Step '启动服务'
Invoke-ServiceCommand -Action start | Out-Null

# ------------------------------------------------------------
# 5. 健康检查（失败自动回滚）
# ------------------------------------------------------------
Write-Step "健康检查（最多 90 秒，端口 $AppPort）"
$healthy = Wait-SrtpHealthy -Port $AppPort -TimeoutSec 90

if (-not $healthy) {
    Write-Log '新版本健康检查失败！' -Level ERROR
    if ($previous -and $previous -ne $activeVersion) {
        Write-Log "自动回滚到上一个版本: $previous" -Level WARN
        Stop-SrtpService | Out-Null
        Set-CurrentJunction -Version $previous
        Invoke-ServiceCommand -Action start | Out-Null
        if (Wait-SrtpHealthy -Port $AppPort -TimeoutSec 90) {
            Write-Log "已回滚到 $previous 并恢复正常" -Level OK
        } else {
            Write-Log '回滚后健康检查仍未通过，需要人工介入！' -Level ERROR
        }
    } else {
        Write-Log '没有可回滚的历史版本，服务可能处于不可用状态。' -Level ERROR
    }
    Write-Log '请检查 C:\srtp\logs 下的应用日志与 deploy.log' -Level ERROR
    throw '部署失败：新版本未通过健康检查。'
}

# ------------------------------------------------------------
# 6. 记录版本
# ------------------------------------------------------------
if (-not $EnvOnly) {
    if ($previous -and $previous -ne $Version) {
        Set-CurrentVersion -Version $Version -PreviousVersion $previous
    } else {
        Set-CurrentVersion -Version $Version
    }
}

$duration = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
$mysqlState = Get-MysqlState
$summary = [ordered]@{
    ok              = $true
    mode            = $mode
    version         = $activeVersion
    previous        = $previous
    host            = $env:COMPUTERNAME
    port            = $AppPort
    service_state   = (Get-ServiceState)
    mysql_state     = $mysqlState.Status
    healthy         = $true
    duration_sec    = $duration
    releases_count  = (Get-ReleaseVersions).Count
    finished_at     = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
}

Write-Log "======== 部署成功: $activeVersion（用时 ${duration}s）========" -Level OK
Write-Host '===SRTP_DEPLOY_JSON_BEGIN==='
Write-Host ($summary | ConvertTo-Json -Depth 4 -Compress)
Write-Host '===SRTP_DEPLOY_JSON_END==='
