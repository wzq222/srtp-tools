<#
.SYNOPSIS
    SRTP 服务器一次性初始化脚本（Windows Server 2022）。

.DESCRIPTION
    在被部署的 Windows 服务器上以【管理员身份】运行一次，完成：
      1. 校验 OpenSSH Server（GitHub Actions 通过它接入）
      2. 安装 JRE 17（Temurin 便携版）
      3. 安装 Python 3.12（算法桥接脚本只需要标准库；numpy 作为可选便利项）
      4. 安装 MySQL 8（zip 便携版 + 独立服务 SRTPMySQL，绑定 127.0.0.1）
      5. 创建 C:\srtp 目录布局（releases / shared / logs / tools ...）
      6. 写入 ACL 锁定的密钥文件 C:\srtp\shared\.env
      7. 渲染并注册开机自启的 Windows 服务（WinSW 包装）

    幂等：已安装的组件会被跳过，可安全重复执行。

.PARAMETER EnvFile
    由 GitHub Actions 上传的密钥文件（KEY=VALUE 格式）路径。
    必须至少包含 DB_PASSWORD；其余键会被原样下发到服务进程的环境变量。

.PARAMETER GenerateDbPassword
    当 EnvFile 中缺少 DB_PASSWORD 时，自动生成一个强随机密码并回写到 .env。
    注意：生成的密码会打印在控制台，请立刻保存到 GitHub Secrets 的 DB_PASSWORD。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\bootstrap-server.ps1 `
        -EnvFile C:\srtp\incoming\secrets.env
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EnvFile,
    [switch]$GenerateDbPassword,
    [switch]$SkipJre,
    [switch]$SkipPython,
    [switch]$SkipMysql,
    [string]$MysqlDownloadUrl = 'https://dev.mysql.com/get/Downloads/MySQL-8.0/mysql-8.0.39-winx64.zip',
    [string]$PythonVersion = '3.12.10',
    [int]$AppPort = 8090
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Common.ps1"

# 下载大文件时关闭进度条渲染，否则 PowerShell 5.1 会慢十倍以上
$ProgressPreference = 'SilentlyContinue'

$paths = Get-SrtpPaths

Write-Host ''
Write-Log '======== SRTP 服务器初始化开始 ========'

# ------------------------------------------------------------
# 0. 前置检查
# ------------------------------------------------------------
Assert-Administrator
Write-Log "运行账户: $([Security.Principal.WindowsIdentity]::GetCurrent().Name)"

$os = Get-CimInstance Win32_OperatingSystem
Write-Log ("操作系统: {0}" -f $os.Caption)
$memGB = [math]::Round($os.TotalVisibleMemorySize / 1MB, 1)
Write-Log ("物理内存: {0} GB" -f $memGB)
if ($memGB -lt 3) {
    Write-Log '内存较紧（<3GB）：已按保守参数配置 JVM 与 MySQL，请勿再调大。' -Level WARN
}

# ------------------------------------------------------------
# 1. 目录布局
# ------------------------------------------------------------
Write-Step '创建目录布局'
foreach ($d in @($paths.Tools, $paths.Releases, $paths.Shared, $paths.Service,
                 $paths.Logs, $paths.Incoming, $paths.Winsw, (Join-Path $paths.Scripts 'winsw'))) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}
Write-Log "目录就绪: $($paths.Root)" -Level OK

# ------------------------------------------------------------
# 2. OpenSSH Server 校验（Actions 的接入通道）
# ------------------------------------------------------------
Write-Step '校验 OpenSSH Server'
$sshd = Get-Service -Name sshd -ErrorAction SilentlyContinue
if ($null -eq $sshd) {
    Write-Log '未检测到 sshd 服务！GitHub Actions 无法接入。' -Level ERROR
    Write-Log '请先在服务器上以管理员身份执行 deploy/scripts/enable-openssh.ps1，并放通安全组 22 端口。' -Level ERROR
    throw 'OpenSSH Server 未安装，初始化终止。'
} else {
    Write-Log "sshd 服务状态: $($sshd.Status)" -Level OK
    if ($sshd.Status -ne 'Running') { Start-Service sshd; Write-Log '已启动 sshd' -Level OK }
}

# ------------------------------------------------------------
# 3. JRE 17（Temurin 便携版）
# ------------------------------------------------------------
if (-not $SkipJre) {
    Write-Step '安装 JRE 17 (Temurin)'
    $javaExe = Join-Path $paths.Jre 'bin\java.exe'
    if (Test-Path $javaExe) {
        Write-Log "已存在，跳过: $((& $javaExe -version 2>&1 | Select-Object -First 1))" -Level OK
    } else {
        $tmp = Join-Path $env:TEMP 'srtp-jre17.zip'
        $api = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk'
        Invoke-Download -Url $api -OutFile $tmp

        $extractTo = Join-Path $env:TEMP 'srtp-jre17'
        Expand-ZipArchive -ZipPath $tmp -Destination $extractTo
        # 解压后形如 jdk-17.0.x+y-jre，取其中带 bin\java.exe 的那一层
        $inner = Get-ChildItem -Path $extractTo -Directory |
                 Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
                 Select-Object -First 1
        if ($null -eq $inner) { throw 'JRE 解压结果异常，未找到 bin\java.exe' }

        if (Test-Path $paths.Jre) { Remove-Item $paths.Jre -Recurse -Force }
        Move-Item -Path $inner.FullName -Destination $paths.Jre
        Remove-Item $tmp, $extractTo -Recurse -Force -ErrorAction SilentlyContinue
        Write-Log "JRE 安装完成: $((& $javaExe -version 2>&1 | Select-Object -First 1))" -Level OK
    }
} else { Write-Log '按参数跳过 JRE 安装' -Level WARN }

# ------------------------------------------------------------
# 4. Python 3.12
# ------------------------------------------------------------
if (-not $SkipPython) {
    Write-Step "安装 Python $PythonVersion"
    $pyExe = Join-Path $paths.Python 'python.exe'
    if (Test-Path $pyExe) {
        Write-Log "已存在，跳过: $((& $pyExe --version 2>&1))" -Level OK
    } else {
        $installer = Join-Path $env:TEMP "python-$PythonVersion-amd64.exe"
        $url = "https://www.python.org/ftp/python/$PythonVersion/python-$PythonVersion-amd64.exe"
        Invoke-Download -Url $url -OutFile $installer

        $args = @(
            '/quiet', 'InstallAllUsers=1', 'PrependPath=0',
            'Include_test=0', 'Include_doc=0', 'Include_launcher=0',
            'Include_tcltk=0', 'Include_pip=1', 'SimpleInstall=1',
            "TargetDir=$($paths.Python)"
        )
        Write-Log "静默安装 Python 到 $($paths.Python)"
        $proc = Start-Process -FilePath $installer -ArgumentList $args -Wait -PassThru
        if ($proc.ExitCode -ne 0) { throw "Python 安装失败，退出码 $($proc.ExitCode)" }
        Remove-Item $installer -Force -ErrorAction SilentlyContinue

        if (-not (Test-Path $pyExe)) { throw 'Python 安装后未找到 python.exe' }
        Write-Log "Python 安装完成: $((& $pyExe --version 2>&1))" -Level OK
    }

    # numpy 为可选（自定义算法可能用到），失败不影响部署
    Write-Step '安装 Python 可选依赖 (numpy)'
    try {
        & $pyExe -m pip install --disable-pip-version-check --quiet --no-warn-script-location numpy 2>&1 |
            ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -eq 0) { Write-Log 'numpy 安装完成' -Level OK }
        else { Write-Log "numpy 安装返回 $LASTEXITCODE（不影响内置算法）" -Level WARN }
    } catch {
        Write-Log "numpy 安装失败（不影响内置算法）: $($_.Exception.Message)" -Level WARN
    }
} else { Write-Log '按参数跳过 Python 安装' -Level WARN }

# ------------------------------------------------------------
# 5. WinSW 服务包装器
# ------------------------------------------------------------
Write-Step '安装 WinSW 服务包装器'
if (-not (Test-Path $paths.ServiceExe)) {
    $winswExe = Join-Path $paths.Winsw 'WinSW-x64.exe'
    if (-not (Test-Path $winswExe)) {
        Invoke-Download -Url 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe' -OutFile $winswExe
    }
    Copy-Item -Path $winswExe -Destination $paths.ServiceExe -Force
    Write-Log "WinSW 就位: $($paths.ServiceExe)" -Level OK
} else {
    Write-Log 'WinSW 已存在，跳过' -Level OK
}

# 服务模板由 CI 上传到 scripts\winsw\ 下，这里只做存在性校验
if (-not (Test-Path $paths.XmlTpl)) {
    throw "缺少服务模板: $($paths.XmlTpl)（应由 CI 上传 deploy/winsw/srtp-server.xml）"
}
Write-Log "服务模板就位: $($paths.XmlTpl)" -Level OK

# ------------------------------------------------------------
# 6. MySQL 8
# ------------------------------------------------------------
if (-not $SkipMysql) {
    Write-Step '安装 MySQL 8'
    $mysqld = Join-Path $paths.Mysql 'bin\mysqld.exe'
    $mysqlCli = Join-Path $paths.Mysql 'bin\mysql.exe'
    $iniPath = Join-Path $paths.Mysql 'my.ini'

    if (-not (Test-Path $mysqld)) {
        if (-not (Test-Path 'C:\Windows\System32\vcruntime140_1.dll')) {
            Write-Log '缺少 VC++ 2019 运行库 (vcruntime140_1.dll)，MySQL 可能无法启动。' -Level WARN
            Write-Log '如启动失败，请安装: https://aka.ms/vs/17/release/vc_redist.x64.exe' -Level WARN
        }
        $zip = Join-Path $env:TEMP 'mysql8.zip'
        Invoke-Download -Url $MysqlDownloadUrl -OutFile $zip
        $tmpMysql = Join-Path $env:TEMP 'srtp-mysql'
        Expand-ZipArchive -ZipPath $zip -Destination $tmpMysql
        $inner = Get-ChildItem -Path $tmpMysql -Directory |
                 Where-Object { Test-Path (Join-Path $_.FullName 'bin\mysqld.exe') } |
                 Select-Object -First 1
        if ($null -eq $inner) { throw 'MySQL 解压结果异常，未找到 bin\mysqld.exe' }
        if (Test-Path $paths.Mysql) { Remove-Item $paths.Mysql -Recurse -Force }
        Move-Item -Path $inner.FullName -Destination $paths.Mysql
        Remove-Item $zip, $tmpMysql -Recurse -Force -ErrorAction SilentlyContinue
        Write-Log "MySQL 解压完成: $($paths.Mysql)" -Level OK
    } else {
        Write-Log 'MySQL 二进制已存在，跳过解压' -Level OK
    }

    # my.ini —— 针对 2GB 内存做保守配置，且只监听本机
    # 若需调参，可在备份后修改本文件，但不要用 64MB 以下的值
    if (-not (Test-Path $iniPath)) {
        $ini = @"
[mysqld]
basedir=$($paths.Mysql -replace '\\','/')
datadir=$($paths.Mysql -replace '\\','/')/data
port=3306
bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_general_ci
default-storage-engine=InnoDB
max_connections=60
performance_schema=OFF
innodb_buffer_pool_size=128M
innodb_log_file_size=48M
skip-name-resolve
log-error=$($paths.Logs -replace '\\','/')/mysql-error.log

[client]
port=3306
default-character-set=utf8mb4

[mysql]
default-character-set=utf8mb4
"@
        [System.IO.File]::WriteAllText($iniPath, $ini, (New-Object System.Text.UTF8Encoding($false)))
        Write-Log "已生成 my.ini（buffer_pool=128M / 仅监听 127.0.0.1）" -Level OK
    }

    # 初始化数据目录
    $dataDir = Join-Path $paths.Mysql 'data'
    if (-not (Test-Path $dataDir)) {
        Write-Log '初始化 MySQL 数据目录（root 初始为空密码）'
        & $mysqld "--defaults-file=$iniPath" --initialize-insecure --console 2>&1 |
            ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) { throw "MySQL 初始化失败，退出码 $LASTEXITCODE" }
        Write-Log 'MySQL 数据目录初始化完成' -Level OK
    }

    # 注册并启动服务
    $mySvc = Get-Service -Name 'SRTPMySQL' -ErrorAction SilentlyContinue
    if ($null -eq $mySvc) {
        Write-Log '注册 MySQL 服务 SRTPMySQL'
        & $mysqld "--install" 'SRTPMySQL' "--defaults-file=$iniPath" 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) { throw "MySQL 服务注册失败，退出码 $LASTEXITCODE" }
        & sc.exe config SRTPMySQL start= auto | Out-Null
    }
    $mySvc = Get-Service -Name 'SRTPMySQL'
    if ($mySvc.Status -ne 'Running') { Start-Service SRTPMySQL }
    # 等待端口就绪
    $ok = $false
    for ($i = 0; $i -lt 30; $i++) {
        if (Test-PortOpen -Port 3306) { $ok = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ok) { Write-Log 'MySQL 端口 3306 未就绪，请检查日志' -Level WARN }
    else { Write-Log 'MySQL 服务已启动' -Level OK }
} else { Write-Log '按参数跳过 MySQL 安装' -Level WARN }

# ------------------------------------------------------------
# 7. 读取 CI 下发的密钥文件
# ------------------------------------------------------------
Write-Step '处理密钥 / 环境变量'
if (-not (Test-Path $EnvFile)) { throw "未找到密钥文件: $EnvFile" }
$envMap = Read-EnvFile -Path $EnvFile
if ($envMap.Count -eq 0) { throw "密钥文件为空: $EnvFile" }

if (-not $envMap.Contains('DB_PASSWORD') -or [string]::IsNullOrWhiteSpace($envMap['DB_PASSWORD'])) {
    if ($GenerateDbPassword) {
        $bytes = New-Object byte[] 18
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $generated = ([Convert]::ToBase64String($bytes)) -replace '[+/=]', 'x'
        $envMap['DB_PASSWORD'] = $generated
        Write-Host ''
        Write-Log '=========================================================' -Level WARN
        Write-Log "已生成 MySQL root 密码：$generated" -Level WARN
        Write-Log '请立刻把它填入 GitHub 仓库的 Secret: DB_PASSWORD' -Level WARN
        Write-Log '密码只显示这一次！' -Level WARN
        Write-Log '=========================================================' -Level WARN
        Write-Host ''
    } else {
        throw '密钥文件缺少 DB_PASSWORD。请在 GitHub Secrets 配置 DB_PASSWORD，或加 -GenerateDbPassword 让脚本生成。'
    }
}
$dbPassword = $envMap['DB_PASSWORD']

# 补齐默认值（缺省即用本机合理值）
if (-not $envMap.Contains('DB_USERNAME') -or [string]::IsNullOrWhiteSpace($envMap['DB_USERNAME'])) {
    $envMap['DB_USERNAME'] = 'root'
}
if (-not $envMap.Contains('DB_URL') -or [string]::IsNullOrWhiteSpace($envMap['DB_URL'])) {
    $envMap['DB_URL'] = "jdbc:mysql://127.0.0.1:3306/srtp_tsp?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=UTF-8"
}
$envMap['PYTHON_EXECUTABLE'] = Join-Path $paths.Python 'python.exe'
$envMap['PYTHON_SCRIPT_DIR'] = 'python'
$envMap['SERVER_PORT'] = "$AppPort"

Save-EnvFile -Path $paths.EnvFile -Map $envMap
Protect-SecretFile -Path $paths.EnvFile
Remove-Item $EnvFile -Force -ErrorAction SilentlyContinue

# ------------------------------------------------------------
# 8. 初始化数据库结构与 root 密码
# ------------------------------------------------------------
if (-not $SkipMysql) {
    Write-Step '初始化数据库 srtp_tsp'
    $mysqlCli = Join-Path $paths.Mysql 'bin\mysql.exe'

    # 密码可能已设置过：先按已知密码连，连不上再按空密码设置
    $connected = $false
    & $mysqlCli -h 127.0.0.1 -u root "-p$dbPassword" -e 'SELECT 1;' 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $connected = $true
        Write-Log 'root 密码已生效，跳过设置' -Level OK
    } else {
        Write-Log '尝试按初始空密码设置 root 密码'
        $sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$dbPassword';FLUSH PRIVILEGES;"
        & $mysqlCli -h 127.0.0.1 -u root -e $sql 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -eq 0) { $connected = $true; Write-Log 'root 密码设置完成' -Level OK }
        else { Write-Log '设置 root 密码失败，请手工排查' -Level WARN }
    }

    if ($connected) {
        $schema = Join-Path $paths.Scripts 'sql\schema.sql'
        if (Test-Path $schema) {
            $schemaForSql = $schema -replace '\\', '/'
            & $mysqlCli -h 127.0.0.1 -u root "-p$dbPassword" --default-character-set=utf8mb4 -e "source $schemaForSql" 2>&1 |
                ForEach-Object { Write-Log $_ }
            if ($LASTEXITCODE -eq 0) { Write-Log '数据库结构导入完成 (srtp_tsp)' -Level OK }
            else { Write-Log '数据库结构导入返回非 0，请检查 schema.sql' -Level WARN }
        } else {
            Write-Log "未找到 $schema，跳过结构导入" -Level WARN
        }
    }
}

# ------------------------------------------------------------
# 9. 渲染服务定义
# ------------------------------------------------------------
Write-Step '渲染服务定义'
New-ServiceXmlFromTemplate -EnvMap $envMap
Protect-SecretFile -Path $paths.ServiceXml

# ------------------------------------------------------------
# 10. 已有发布版本则注册并启动服务
# ------------------------------------------------------------
Write-Step '注册 Windows 服务'
$cur = Get-CurrentVersion
if ($null -eq $cur) {
    Write-Log '尚无任何发布版本，服务暂不注册。请先运行 "Production Release (Promote)" 工作流完成首次部署。' -Level WARN
} else {
    Set-CurrentJunction -Version $cur
    Invoke-ServiceCommand -Action install | Out-Null
    Invoke-ServiceCommand -Action start   | Out-Null
    if (Wait-SrtpHealthy -Port $AppPort -TimeoutSec 120) {
        Write-Log "服务已启动并通过健康检查: http://127.0.0.1:$AppPort" -Level OK
    } else {
        Write-Log '服务启动后健康检查未通过，请查看 C:\srtp\logs 下的日志' -Level WARN
    }
}

Write-Host ''
Write-Log '======== 初始化完成 ========' -Level OK
Write-Log "目录:      $($paths.Root)"
Write-Log "JRE:       $($paths.Jre)"
Write-Log "Python:    $($paths.Python)"
Write-Log "MySQL:     $($paths.Mysql)"
Write-Log "密钥文件:  $($paths.EnvFile)  (ACL 已锁定)"
Write-Log "服务名:    srtp-server (开机自启)"
Write-Log '下一步: 在 GitHub 上运行 "Production Release (Promote)" 工作流部署应用。'
