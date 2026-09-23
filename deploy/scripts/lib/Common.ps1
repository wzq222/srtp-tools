# ============================================================
#  Common.ps1 —— SRTP 服务器端部署公共函数库
#  被 bootstrap-server.ps1 / deploy.ps1 / status.ps1 / rollback.ps1 复用
#  兼容 Windows PowerShell 5.1（Windows Server 2022 默认版本）
# ============================================================

$ErrorActionPreference = 'Stop'

# ---------- 目录布局（全部集中在 C:\srtp 下） ----------
function Get-SrtpPaths {
    $root = 'C:\srtp'
    return @{
        Root       = $root
        Tools      = Join-Path $root 'tools'
        Jre        = Join-Path $root 'tools\jre17'
        Python     = Join-Path $root 'tools\python312'
        Winsw      = Join-Path $root 'tools\winsw'
        Mysql      = Join-Path $root 'mysql'
        Releases   = Join-Path $root 'releases'
        Shared     = Join-Path $root 'shared'
        Service    = Join-Path $root 'service'
        Scripts    = Join-Path $root 'scripts'
        Logs       = Join-Path $root 'logs'
        Incoming   = Join-Path $root 'incoming'
        Current    = Join-Path $root 'current'          # junction -> releases\<version>
        EnvFile    = Join-Path $root 'shared\.env'      # ACL 锁定的密钥文件
        CurrentTxt = Join-Path $root 'shared\current.txt'
        PrevTxt    = Join-Path $root 'shared\previous.txt'
        ServiceXml = Join-Path $root 'service\srtp-server.xml'
        ServiceExe = Join-Path $root 'service\srtp-server.exe'
        XmlTpl     = Join-Path $root 'scripts\winsw\srtp-server.xml'
        DeployLog  = Join-Path $root 'logs\deploy.log'
    }
}

# ---------- 日志 ----------
function Write-Log {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [ValidateSet('INFO', 'OK', 'WARN', 'ERROR')][string]$Level = 'INFO'
    )
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $line = "[$stamp][$Level] $Message"
    switch ($Level) {
        'OK'    { Write-Host $line -ForegroundColor Green }
        'WARN'  { Write-Host $line -ForegroundColor Yellow }
        'ERROR' { Write-Host $line -ForegroundColor Red }
        default { Write-Host $line }
    }
    try {
        $paths = Get-SrtpPaths
        if (Test-Path $paths.Logs) {
            Add-Content -Path $paths.DeployLog -Value $line -Encoding UTF8
        }
    } catch { }
}

function Write-Step { param([string]$Text) Write-Log "==> $Text" }

# ---------- 前置检查 ----------
function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw '本脚本必须以管理员身份运行（Administrator）。'
    }
}

function Test-PortOpen {
    param([string]$ComputerName = '127.0.0.1', [int]$Port = 8090, [int]$TimeoutMs = 2000)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($ComputerName, $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        return ($ok -and $client.Connected)
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-SrtpHealthy {
    param(
        [int]$Port = 8090,
        [int]$TimeoutSec = 90
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastError = ''
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen -Port $Port -TimeoutMs 1500) {
            try {
                $resp = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 `
                    -Uri ("http://127.0.0.1:{0}/actuator/health" -f $Port)
                if ($resp.StatusCode -eq 200 -and $resp.Content -match '"status"\s*:\s*"UP"') {
                    return $true
                }
                $lastError = "health 返回: $($resp.Content)"
            } catch {
                $lastError = $_.Exception.Message
            }
        } else {
            $lastError = "端口 $Port 尚未监听"
        }
        Start-Sleep -Seconds 3
    }
    Write-Log "健康检查超时（${TimeoutSec}s）：$lastError" -Level WARN
    return $false
}

function Invoke-Download {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$OutFile
    )
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Write-Log "下载: $Url"
    $parent = Split-Path -Parent $OutFile
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutFile -TimeoutSec 900
    $size = (Get-Item $OutFile).Length
    if ($size -le 0) { throw "下载失败（文件为空）: $Url" }
    Write-Log ("下载完成: {0} ({1:N1} MB)" -f $OutFile, ($size / 1MB)) -Level OK
}

function Expand-ZipArchive {
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (Test-Path $Destination) { Remove-Item $Destination -Recurse -Force }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Expand-Archive -Path $ZipPath -DestinationPath $Destination -Force
}

# ---------- 密钥文件读写（.env） ----------
function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $map = [ordered]@{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($raw in Get-Content -Path $Path -Encoding UTF8) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }
        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1)
        $map[$key] = $val
    }
    return $map
}

function Save-EnvFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Map
    )
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('# ============================================================')
    [void]$sb.AppendLine('# SRTP 部署环境变量 —— 由 GitHub Actions 从 Repository Secrets 下发')
    [void]$sb.AppendLine('# 本文件 ACL 已锁定：仅 SYSTEM 与 Administrators 可读。')
    [void]$sb.AppendLine('# 最后更新: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    [void]$sb.AppendLine('# ============================================================')
    foreach ($k in $Map.Keys) {
        [void]$sb.AppendLine("$k=$($Map[$k])")
    }
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    # 用 UTF8 无 BOM 写入，避免解析出多余的 BOM 字符
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $sb.ToString(), $utf8NoBom)
}

<#
.SYNOPSIS
    锁定敏感文件的访问权限：仅 SYSTEM 与本机 Administrators 可读。
    这是「保存到不可查看的文件内部」的落地方式。
#>
function Protect-SecretFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path $Path)) { return }
    $acl = Get-Acl $Path
    $acl.SetAccessRuleProtection($true, $false)   # 断开继承，移除所有继承来的 ACE
    $acl.Access | ForEach-Object { [void]$acl.RemoveAccessRule($_) }

    $system = New-Object System.Security.AccessControl.FileSystemAccessRule(
        'SYSTEM', 'FullControl', 'Allow')
    $admins = New-Object System.Security.AccessControl.FileSystemAccessRule(
        'BUILTIN\Administrators', 'FullControl', 'Allow')

    $acl.AddAccessRule($system)
    $acl.AddAccessRule($admins)
    try {
        Set-Acl -Path $Path -AclObject $acl
        Write-Log "已锁定权限: $Path" -Level OK
    } catch {
        Write-Log "锁定权限失败（$Path）: $($_.Exception.Message)" -Level WARN
    }
}

# ---------- 版本 / junction ----------
function Get-ReleaseVersions {
    $paths = Get-SrtpPaths
    if (-not (Test-Path $paths.Releases)) { return @() }
    $items = Get-ChildItem -Path $paths.Releases -Directory -ErrorAction SilentlyContinue
    # 目录名形如 1.0.0-42-a1b2c3d，按 run 号降序
    return @($items | Sort-Object Name -Descending | Select-Object -ExpandProperty Name)
}

function Get-CurrentVersion {
    $paths = Get-SrtpPaths
    if (-not (Test-Path $paths.CurrentTxt)) { return $null }
    $v = (Get-Content -Path $paths.CurrentTxt -Encoding UTF8 -First 1).Trim()
    if ($v -eq '') { return $null }
    return $v
}

function Get-PreviousVersion {
    $paths = Get-SrtpPaths
    if (-not (Test-Path $paths.PrevTxt)) { return $null }
    $v = (Get-Content -Path $paths.PrevTxt -Encoding UTF8 -First 1).Trim()
    if ($v -eq '') { return $null }
    return $v
}

function Set-CurrentVersion {
    param(
        [Parameter(Mandatory = $true)][string]$Version,
        [string]$PreviousVersion = $null
    )
    $paths = Get-SrtpPaths
    if ($PSBoundParameters.ContainsKey('PreviousVersion') -and $PreviousVersion) {
        [System.IO.File]::WriteAllText($paths.PrevTxt, $PreviousVersion, (New-Object System.Text.UTF8Encoding($false)))
    }
    [System.IO.File]::WriteAllText($paths.CurrentTxt, $Version, (New-Object System.Text.UTF8Encoding($false)))
}

<#
.SYNOPSIS
    把 C:\srtp\current 指向 releases\<Version>（用于原子切换版本）。
#>
function Set-CurrentJunction {
    param([Parameter(Mandatory = $true)][string]$Version)
    $paths = Get-SrtpPaths
    $target = Join-Path $paths.Releases $Version
    if (-not (Test-Path $target)) { throw "目标发布目录不存在: $target" }

    if (Test-Path $paths.Current) {
        # 只删 junction 本身，不影响其指向的真实目录
        [System.IO.Directory]::Delete($paths.Current, $false)
    }
    New-Item -ItemType Junction -Path $paths.Current -Target $target | Out-Null
    Write-Log "current -> $target" -Level OK
}

# ---------- Windows 服务（WinSW） ----------
function Invoke-ServiceCommand {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('install', 'uninstall', 'start', 'stop', 'restart', 'status')][string]$Action
    )
    $paths = Get-SrtpPaths
    if (-not (Test-Path $paths.ServiceExe)) {
        throw "未找到服务包装器: $($paths.ServiceExe)（请先运行 bootstrap-server.ps1）"
    }
    $output = & $paths.ServiceExe $Action 2>&1
    $code = $LASTEXITCODE
    if ($output) { Write-Log ("[{0}] {1}" -f $Action, ($output -join ' | ')) }
    return @{ ExitCode = $code; Output = $output }
}

function Stop-SrtpService {
    $r = Invoke-ServiceCommand -Action stop
    # WinSW 在服务已停止时返回非 0，属正常情况
    Start-Sleep -Seconds 2
    return $r
}

function Start-SrtpService {
    return Invoke-ServiceCommand -Action start
}

<#
.SYNOPSIS
    根据模板与 .env 渲染 WinSW 服务定义文件（把密钥以 <env> 形式注入服务进程）。
#>
function New-ServiceXmlFromTemplate {
    param([Parameter(Mandatory = $true)]$EnvMap)
    $paths = Get-SrtpPaths
    if (-not (Test-Path $paths.XmlTpl)) { throw "未找到服务模板: $($paths.XmlTpl)" }

    $tpl = Get-Content -Path $paths.XmlTpl -Raw -Encoding UTF8
    $tpl = $tpl.Replace('__JRE__', $paths.Jre)
    $tpl = $tpl.Replace('__CURRENT__', $paths.Current)
    $tpl = $tpl.Replace('__LOGS__', $paths.Logs)

    $envLines = New-Object System.Text.StringBuilder
    foreach ($k in $EnvMap.Keys) {
        $val = [System.Security.SecurityElement]::Escape([string]$EnvMap[$k])
        [void]$envLines.AppendLine(('  <env name="{0}" value="{1}" />' -f $k, $val))
    }
    $tpl = $tpl.Replace('  __ENV_BLOCK__', $envLines.ToString().TrimEnd())

    if (-not (Test-Path $paths.Service)) { New-Item -ItemType Directory -Path $paths.Service -Force | Out-Null }
    [System.IO.File]::WriteAllText($paths.ServiceXml, $tpl, (New-Object System.Text.UTF8Encoding($false)))
    Write-Log "已渲染服务定义: $($paths.ServiceXml)" -Level OK
}

# ---------- 状态采集 ----------
function Get-ServiceState {
    $svc = Get-Service -Name 'srtp-server' -ErrorAction SilentlyContinue
    if ($null -eq $svc) { return 'NotInstalled' }
    return $svc.Status.ToString()
}

function Get-MysqlState {
    foreach ($name in @('SRTPMySQL', 'MySQL80', 'MySQL')) {
        $svc = Get-Service -Name $name -ErrorAction SilentlyContinue
        if ($null -ne $svc) { return @{ Name = $name; Status = $svc.Status.ToString() } }
    }
    return @{ Name = $null; Status = 'NotFound' }
}

function Get-ReleaseInfo {
    param([Parameter(Mandatory = $true)][string]$Version)
    $paths = Get-SrtpPaths
    $metaPath = Join-Path (Join-Path $paths.Releases $Version) 'release.json'
    if (-not (Test-Path $metaPath)) { return $null }
    try {
        return (Get-Content -Path $metaPath -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch {
        return $null
    }
}
