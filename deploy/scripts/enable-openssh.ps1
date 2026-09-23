<#
.SYNOPSIS
    在 Windows 服务器上启用 OpenSSH Server（GitHub Actions 的接入通道）。

.DESCRIPTION
    这是一个【人工一次性】脚本：SSH 还没通之前，无法通过 Actions 执行任何东西，
    所以需要在服务器的「远程连接」桌面里以管理员身份运行本脚本。

    脚本会：
      1. 安装 OpenSSH Server 功能
      2. 设置 sshd / ssh-agent 服务为自动启动并立即启动
      3. 放通 Windows 防火墙 22 端口
      4. 可选：写入部署公钥到 administrators_authorized_keys 并修正 ACL
         （Administrator 属于管理员组，Windows OpenSSH 只认这个文件，
           不会读取 C:\Users\Administrator\.ssh\authorized_keys）
      5. 打印云安全组需要放通的端口提醒

.EXAMPLE
    # 仅启用 SSH（之后用密码登录）
    powershell -ExecutionPolicy Bypass -File .\enable-openssh.ps1

.EXAMPLE
    # 同时写入部署公钥（推荐，CI 用密钥免密登录）
    powershell -ExecutionPolicy Bypass -File .\enable-openssh.ps1 `
        -PublicKey "ssh-ed25519 AAAAC3Nza... your@ci"
#>
[CmdletBinding()]
param(
    [string]$PublicKey = '',
    [switch]$SetDefaultShellToPowerShell
)

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw '请以管理员身份运行本脚本。'
}

function Step { param([string]$t) Write-Host "==> $t" -ForegroundColor Cyan }

Write-Host ''
Write-Host '======== 启用 OpenSSH Server ========' -ForegroundColor Cyan

# ---------- 1. 安装功能 ----------
Step '检查 / 安装 OpenSSH Server 功能'
$cap = Get-WindowsCapability -Online -Name 'OpenSSH.Server*' -ErrorAction SilentlyContinue
if ($null -eq $cap) {
    Write-Host '未查询到 OpenSSH.Server 功能，尝试直接安装...' -ForegroundColor Yellow
    Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null
} elseif ($cap.State -ne 'Installed') {
    Write-Host "当前状态: $($cap.State)，开始安装..." -ForegroundColor Yellow
    Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null
} else {
    Write-Host 'OpenSSH Server 已安装' -ForegroundColor Green
}

# ---------- 2. 服务 ----------
Step '配置 sshd / ssh-agent 服务'
Set-Service -Name sshd -StartupType Automatic
Set-Service -Name ssh-agent -StartupType Automatic -ErrorAction SilentlyContinue
if ((Get-Service sshd).Status -ne 'Running') { Start-Service sshd }
Start-Service ssh-agent -ErrorAction SilentlyContinue
Write-Host "sshd 服务状态: $((Get-Service sshd).Status)" -ForegroundColor Green

# ---------- 3. 防火墙 ----------
Step '放通 Windows 防火墙 22 端口'
$rule = Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
if ($null -eq $rule) {
    New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (sshd)' `
        -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 | Out-Null
    Write-Host '已创建入站规则 OpenSSH-Server-In-TCP' -ForegroundColor Green
} else {
    Set-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -Enabled True -Action Allow
    Write-Host '已启用既有规则 OpenSSH-Server-In-TCP' -ForegroundColor Green
}

# ---------- 4. 写入部署公钥 ----------
if (-not [string]::IsNullOrWhiteSpace($PublicKey)) {
    Step '写入部署公钥（管理员专用文件）'
    $sshDir = Join-Path $env:ProgramData 'ssh'
    if (-not (Test-Path $sshDir)) { New-Item -ItemType Directory -Path $sshDir -Force | Out-Null }
    $akPath = Join-Path $sshDir 'administrators_authorized_keys'

    $existing = @()
    if (Test-Path $akPath) { $existing = @(Get-Content -Path $akPath -Encoding UTF8) }
    $keyLine = $PublicKey.Trim()
    if ($existing -notcontains $keyLine) {
        $existing += $keyLine
        Set-Content -Path $akPath -Value $existing -Encoding UTF8
        Write-Host '公钥已写入 administrators_authorized_keys' -ForegroundColor Green
    } else {
        Write-Host '公钥已存在，跳过' -ForegroundColor Green
    }

    # Windows OpenSSH 强制要求：该文件只能由 SYSTEM 与 Administrators 访问
    Step '修正 administrators_authorized_keys 权限（Windows OpenSSH 硬性要求）'
    icacls $akPath /inheritance:r | Out-Null
    icacls $akPath /grant 'SYSTEM:F' | Out-Null
    icacls $akPath /grant 'BUILTIN\Administrators:F' | Out-Null
    Write-Host '权限已修正' -ForegroundColor Green

    # 关闭管理员账户下的密码登录（可选加固，此处仅提示）
    Write-Host '提示：如需禁用密码登录，可编辑 C:\ProgramData\ssh\sshd_config 后重启 sshd' -ForegroundColor Yellow
}

# ---------- 5. 默认 Shell ----------
if ($SetDefaultShellToPowerShell) {
    Step '把默认 SSH Shell 设为 PowerShell'
    $psPath = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
    New-ItemProperty -Path 'HKLM:\SOFTWARE\OpenSSH' -Name DefaultShell -Value $psPath `
        -PropertyType String -Force | Out-Null
    Write-Host "默认 Shell -> $psPath" -ForegroundColor Green
}

# ---------- 6. 收尾提示 ----------
$ips = Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
       Select-Object -ExpandProperty IPAddress

Write-Host ''
Write-Host '======== 完成 ========' -ForegroundColor Green
Write-Host "本机 IPv4: $($ips -join ', ')"
Write-Host ''
Write-Host '还需在【云控制台的安全组】中确认已放通入方向:' -ForegroundColor Yellow
Write-Host '  22    (SSH，必需)' -ForegroundColor Yellow
Write-Host '  8090  (应用端口，若需公网直接访问则放通，建议限制来源 IP)' -ForegroundColor Yellow
Write-Host '  3306  (MySQL，建议【关闭】—— 数据库仅监听 127.0.0.1)' -ForegroundColor Yellow
Write-Host '  3389  (远程桌面，建议限制来源 IP)' -ForegroundColor Yellow
Write-Host ''
