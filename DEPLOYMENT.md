# SRTP 部署手册（Deployment）

本文件说明如何把本仓库通过 **GitHub Actions** 一键部署到 Windows 服务器（`8.140.194.254`，Windows Server 2022），
以及如何管理密钥、查看状态、回滚版本。

> 仓库为 **Public**，因此任何敏感信息（数据库密码、API Key 等）都**只能**放在
> **GitHub Repository Secrets** 与服务器上 **ACL 锁定的 `.env`** 中，绝不入库。

---

## 0. 架构概览

```
GitHub（push / 手动触发 Actions）
   │  workflow_dispatch:
   │    Server Bootstrap（一次性初始化）
   │    Server Cleanup（清理旧代码）
   │    Production Release (Promote) / Status / Rollback
   ▼
GitHub Actions Runner（ubuntu-latest）
   ├─ 构建前端(Vue) → 后端 static 目录
   ├─ 构建后端(Spring Boot) → 可执行 jar
   ├─ 从 Secrets 渲染 secrets.env（仅内存，不落库）
   ├─ 打 tar.gz（scripts + app + secrets.env）
   ├─ 通过 SSH(scp/ssh) 上传到服务器用户主目录
   ▼
Windows 服务器（SSH / OpenSSH Server）
   ├─ 解包 → C:\srtp\scripts、C:\srtp\incoming\<version>
   ├─ bootstrap-server.ps1：装 JRE17 / Python3.12 / WinSW / MySQL8，建库、写密钥、注册服务
   ├─ deploy.ps1：安置版本 → 切换 current junction → 渲染服务定义 → 重启服务
   ├─ WinSW 服务 srtp-server（JVM 跑 Spring Boot）
   ├─ Python 桥接算法（四算法整合优化.py，按路径扫描加载）
   └─ MySQL 8（SRTPMySQL，仅监听 127.0.0.1）
```

服务器目录布局（`C:\srtp`）：

| 路径 | 用途 |
| --- | --- |
| `releases/<version>` | 每次发布版本（jar + python + srtp-app-source） |
| `current` | junction，指向当前 `releases/<version>`（原子切换） |
| `shared\.env` | ACL 锁定的密钥文件（仅 SYSTEM / Administrators 可读） |
| `shared\current.txt` / `previous.txt` | 当前 / 上一个版本号 |
| `scripts\` | 部署脚本（deploy/status/rollback/bootstrap + lib） |
| `scripts\winsw` / `scripts\sql` | 服务模板 / 建表 SQL |
| `service\srtp-server.xml` | WinSW 渲染后的服务定义 |
| `tools\jre17` `tools\python312` `tools\winsw` `mysql` | 运行时 |
| `logs\` | 应用输出 + deploy.log + mysql-error.log |
| `incoming\` | CI 上传的临时载荷与密钥 |

---

## 1. 前置条件

- 服务器：Windows Server 2022，公网 IP `8.140.194.254`，≥2 GB 内存（当前 2 GB，已按保守参数配置）。
- 服务器在 bootstrap 阶段需要**出网**，以下载 JRE 17 / Python 3.12 / MySQL 8 / WinSW（约数百 MB）。
- 仓库：GitHub，已开启 Actions（Settings → Actions → General → 允许 workflow_dispatch）。
- 本机不需要 GitHub CLI，也不需要手工 push：**代码已推送到 `main`，9 个 Secrets 已配置好**。
  后续改动只需在 Actions 页面点按钮（或再 push）；敏感值一律走 Secrets，不入库。

---

## 2. 配置 GitHub Repository Secrets（必须）

在仓库 **Settings → Secrets and variables → Actions → New repository secret** 中创建：

| Secret 名 | 必填 | 说明 |
| --- | --- | --- |
| `SSH_HOST` | ✅ | 服务器公网 IP：`8.140.194.254` |
| `SSH_PORT` | ⬜ | SSH 端口，默认 `22` |
| `SSH_USER` | ✅ | 服务器管理员账户名（如 `Administrator`） |
| `SSH_PRIVATE_KEY` | ✅* | CI 私钥（ed25519/rsa），与放在服务器上的公钥配对 |
| `SSH_PASSWORD` | ✅* | 管理员密码（与 `SSH_PRIVATE_KEY` 二选一） |
| `DB_PASSWORD` | ✅ | MySQL root 密码。**必须先在这里设定好**，bootstrap 会用它初始化 MySQL 并写入服务器 `.env` |

> `*` 二选一即可，推荐用密钥（`SSH_PRIVATE_KEY`）更安全。
> `**` 虽非强制，但生产环境强烈建议设置，避免空 Key。

> ⚠️ **不要把密码留到运行时自动生成。** 本仓库是 **Public** 仓库，Actions 日志对所有人可见；
> 自动生成的随机密码不属于 Secrets，不会被日志脱敏，一旦打印就永久泄露。
> 因此 `Server Bootstrap` 工作流**刻意不暴露** `bootstrap-server.ps1` 的 `-GenerateDbPassword` 开关，
> `DB_PASSWORD` 必须先在 Secrets 里定好。
>
> 另注：GitHub 的 Repository Secrets **创建后不可回读**（只写）。请自行留存一份明文备份，
> 否则以后想手工登录 MySQL 会比较麻烦。

当前仓库已配置的 9 个 Secret：`SSH_HOST`、`SSH_PORT`、`SSH_USER`、`SSH_PRIVATE_KEY`、
`DB_PASSWORD`、`DB_USERNAME`、`DB_URL`、`APP_API_KEY`、`JWT_SECRET`。

这些值**不会**出现在仓库任何文件里：仅存在于 GitHub Secrets 与服务器 ACL 锁定的 `C:\srtp\shared\.env`。

---

## 3. 服务器首次初始化（1 步手工 + 2 步一键）

顺序：**3.1 在服务器上开启 OpenSSH（唯一必须手工的一步）** → **3.2 跑 Server Bootstrap 工作流** →
**3.3 跑 Production Release (Promote)**。

### 3.1 开启 OpenSSH Server（在服务器上操作，唯一手工步骤）

SSH 还没通之前，Actions 连不上服务器，所以这一步必须在服务器的**远程桌面 / 云控制台 VNC** 里，
以**管理员身份**打开 PowerShell，粘贴执行下面这一整行：

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 | Out-Null; Set-Service sshd -StartupType Automatic; Start-Service sshd; New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (sshd)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 -ErrorAction SilentlyContinue | Out-Null; Set-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -Enabled True -Action Allow -ErrorAction SilentlyContinue; New-Item -ItemType Directory "$env:ProgramData\ssh" -Force | Out-Null; $k='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILaiP7v7lsEKsN8VGPtx/PGPxJaap/DlxjsZbQesp71V srtp-deploy@github-actions'; $f="$env:ProgramData\ssh\administrators_authorized_keys"; $e=@(); if (Test-Path $f) { $e=@(Get-Content $f -Encoding UTF8) }; if ($e -notcontains $k) { $e+=$k; Set-Content $f -Value $e -Encoding UTF8 }; icacls $f /inheritance:r | Out-Null; icacls $f /grant 'SYSTEM:F' | Out-Null; icacls $f /grant 'BUILTIN\Administrators:F' | Out-Null; Get-Service sshd | Select-Object Name,Status,StartType | Format-Table
```

执行结束应看到 `sshd` 的 **Status = Running**。

要点：

- Windows OpenSSH 对**管理员组**账户只认 `C:\ProgramData\ssh\administrators_authorized_keys`，
  **不会**读取 `C:\Users\<用户>\.ssh\authorized_keys`。上面的命令已直接写入该文件并修正 ACL
  （必须仅 `SYSTEM` 与 `Administrators` 可访问，否则 sshd 会拒绝密钥登录）。
- 上面的公钥是**本仓库 CI 专用密钥**的公钥；对应私钥已写入 Secret `SSH_PRIVATE_KEY`。
  私钥在本机的备份位置：`.workbuddy/ssh/srtp_deploy_ed25519`（该目录已被 `.gitignore` 忽略）。
- **务必在云控制台的安全组放通入方向 `22` 端口**，否则 Actions 依然连不上。
- 想改用参数化脚本（逻辑等价）也可以，先把 `deploy/` 放到服务器再执行：

  ```powershell
  powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\enable-openssh.ps1 `
      -PublicKey "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILaiP7v7lsEKsN8VGPtx/PGPxJaap/DlxjsZbQesp71V srtp-deploy@github-actions"
  ```

### 3.2 运行 Server Bootstrap（Actions，一键）

Actions → **Server Bootstrap (首次初始化)** → **Run workflow**（默认参数即可）。

它会自动完成：

1. 校验 / 启动 `sshd`
2. 下载安装 **JRE 17**（Temurin 便携版）→ `C:\srtp\tools\jre17`
3. 安装 **Python 3.12** → `C:\srtp\tools\python312`（numpy 为可选，失败不阻断）
4. 下载安装 **WinSW** → `C:\srtp\service\srtp-server.exe`
5. 下载安装 **MySQL 8**（zip 便携版，服务名 `SRTPMySQL`，**仅监听 127.0.0.1**）
6. 用 Secrets 里的 `DB_PASSWORD` 初始化 MySQL root 密码，并在 `srtp_tsp` 库导入 `schema.sql`
7. 把 `DB_PASSWORD` / `APP_API_KEY` / `JWT_SECRET` 等写入 **ACL 锁定**的 `C:\srtp\shared\.env`，
   并渲染 WinSW 服务定义
8. 幂等：已装好的组件会自动跳过，可安全重复执行

> 首次运行较慢（需下载约 300MB 安装包，期间日志可能长时间无输出），工作流超时已设为 60 分钟。

### 3.3 验证初始化

Actions → **Production Status**（只读巡检），或在服务器上直接运行：

```powershell
powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\status.ps1
```

（此时尚未部署应用，`current_version` 为空属正常。）

---

## 4. 触发部署（GitHub Actions）

仓库推送后，在 **Actions** 页面选择对应工作流 → **Run workflow**：

| 工作流 | 用途 | 关键输入 |
| --- | --- | --- |
| **Server Bootstrap (首次初始化)** | **只跑一次**：装 JRE/Python/WinSW/MySQL，建库、写密钥、注册服务 | `skip_jre` / `skip_python` / `skip_mysql`、`app_port` |
| **Server Cleanup (清理旧代码)** | 部署前清理服务器上以前的无关代码 | `paths`（要删的绝对路径，每行一个）、`dry_run`（先预览再真删） |
| **Production Release (Promote)** | 完整构建并部署（或仅同步密钥） | `env_only`（仅同步密钥并重启）、`version_suffix`、`skip_tests`、`cleanup_paths`（可选，部署前顺带清理） |
| **Production Status** | 只读巡检，输出 JSON 到 Summary | `fail_if_down`（不健康则失败）、`app_port` |
| **Production Rollback** | 回滚版本 | `target`（`previous` 或版本号）、`list_only` |

- **首次使用顺序**：`Server Bootstrap` → `Server Cleanup`（可选）→ `Production Release (Promote)`。
  三个工作流共用同一个 `srtp-production` 并发组，不会互相打断。

- **部署前清理旧代码**：先运行 **Server Cleanup**，`dry_run` 先设为 `true` 预览将要删除的内容（含每个路径的大小），确认无误后再设为 `false` 真正删除。该工作流**只删除你列出的路径**，并内置受保护路径黑名单（`C:\` 根、`C:\Windows`、`C:\Program Files`、`C:\ProgramData`、`C:\Users`、`C:\srtp`），命中即跳过，绝不会误删系统或现有部署目录。详见第 4.5 节。

- **首次部署**：先跑完 `Server Bootstrap`，再运行 Promote（默认完整部署）。`deploy.ps1` 会把 jar
  安置到 `releases/<version>`、注册并启动服务、做健康检查；失败会自动回滚到上一版本。
- **改了 Secrets 后**：运行 Promote 并勾选 `env_only`，无需重新构建即可把新密钥下发并重启服务。
- **Production** 环境（Settings → Environments）可配置"必须人工批准"，给 Promote / Rollback 加审批门禁。

### 4.5 部署前清理服务器旧代码（Server Cleanup）

服务器以前用来做别的事情，部署 SRTP 之前应先把那些无关代码清掉。清理**只发生在 GitHub Actions 运行器连上服务器之后**，由服务端 PowerShell 执行；本机（AI 环境）受网络代理限制无法直连服务器 SSH，因此一切清理/部署都走 Actions。

**为什么安全：**
- 只删除你在 `paths` 输入框里显式列出的绝对路径，绝不递归删整盘。
- 服务端脚本内置受保护路径黑名单（`C:\`、`C:\Windows`、`C:\Program Files`、`C:\Program Files (x86)`、`C:\ProgramData`、`C:\Users`、`C:\srtp`），命中即跳过——保护系统目录与 SRTP 自身部署目录。
- 默认 `dry_run=true`：只统计每个路径的大小、不真正删除。建议第一次先预览，确认无误再 `false` 真删。

**操作步骤：**
1. Actions → **Server Cleanup** → Run workflow：
   - `paths`：默认已填 `C:\deploy`（服务器 C 盘上历史遗留的旧目录）。若还有其他旧代码，可追加每行一个。
   - `dry_run`：先填 `true`
2. 看 Summary / 日志里的 `===SRTP_CLEANUP_JSON_BEGIN===`：确认 `existed`、`sizeBytes`，且系统目录显示 `protected:true`。
3. 再次运行 **Server Cleanup**，`dry_run` 设为 `false`，真正删除。
4. 然后运行 **Production Release (Promote)** 部署 SRTP。

> 提示：Promote 的 `cleanup_paths` 默认即为 `C:\deploy`，因此**直接运行 Promote 也会在部署前自动执行一次真删清理**（等价于 dry_run=false 的 Cleanup）。如果你不希望自动清理，把该输入留空即可。

---

## 5. 密钥如何流转（"不可查看"的落地方式）

```
GitHub Secrets
  → render-secrets.sh     渲染 transfer/secrets.env（仅 Actions 内存，不落库）
  → tar.gz 上传
  → C:\srtp\incoming\secrets.env
  → deploy.ps1 合并到 C:\srtp\shared\.env   （Protect-SecretFile：ACL 仅 SYSTEM/Administrators）
  → New-ServiceXmlFromTemplate 生成 <env> 注入
  → WinSW 把环境变量注入 srtp-server 服务进程
  → Spring Boot 通过 ${DB_PASSWORD} 等读取
```

`C:\srtp\shared\.env` 用 `icacls`/ACL 断开继承，仅 `SYSTEM` 与 `Administrators` 可读；普通用户与其他进程无法读取内容。

---

## 6. 回滚

```bash
# 在 Actions 运行 Production Rollback，target 默认 previous
```

- 切换 `current` junction → 重启服务 → 健康检查通过才算成功。
- 若目标版本不健康，会尝试切回回滚前的版本。
- 仅列举可回滚版本：`Production Rollback` 勾选 `list_only`，或服务器上：

```powershell
powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\rollback.ps1 -ListOnly
```

---

## 7. 故障排查

| 现象 | 排查 |
| --- | --- |
| Actions 卡在「配置 SSH 连接」/ `Connection refused` | 服务器 sshd 未开或安全组没放通 22。回第 3.1 步 |
| SSH 连上但密钥被拒（`Permission denied (publickey)`） | 公钥没写进 `C:\ProgramData\ssh\administrators_authorized_keys`，或该文件 ACL 不对（必须仅 SYSTEM / Administrators）。用第 3.1 步的整行命令重跑一次 |
| `SSH_USER` 不对 | 默认按 `Administrator` 配置。若你的管理员账户名不同，改 Secret `SSH_USER`（注意必须属于 Administrators 组，否则不会读 `administrators_authorized_keys`） |
| Bootstrap 报 `OpenSSH Server 未安装，初始化终止` | 同上，先做第 3.1 步 |
| 服务起不来 / 启动即退 | 看 `C:\srtp\logs\srtp-server.out.log`、`C:\srtp\logs\deploy.log` |
| 健康检查失败（自动回滚） | jar 缺依赖、端口被占、或 `.env` 中 `DB_PASSWORD` 与 MySQL 实际 root 密码不一致 |
| MySQL 起不来 | bootstrap 会提示是否缺 VC++ 2019 运行库；看 `C:\srtp\logs\mysql-error.log`。必要时安装 `vc_redist.x64.exe` |
| `DB_PASSWORD 为空` | 确认 Secret `DB_PASSWORD` 已配置，且与服务器 `.env` 一致 |
| PowerShell 中文乱码 / 语法错误 | 脚本已统一 **UTF-8 BOM**；若你手动改过 `.ps1`，请保存为 UTF-8 BOM 再上传 |
| 部署脚本连不上服务器 | 确认 `SSH_HOST`/`SSH_USER`/密钥或密码 Secret 正确，且安全组 22 已放通 |

常用服务器命令：

```powershell
# 状态巡检
powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\status.ps1 -FailIfDown
# 回滚到上一版本
powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\rollback.ps1 -Target previous
# 重新初始化（幂等，可重复跑）
powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\bootstrap-server.ps1 -EnvFile C:\srtp\incoming\secrets.env
```

---

## 8. 注意事项

- 算法核心文件原名含中文（`四算法整合优化.py`），桥接脚本按**路径扫描**加载，不受文件名变化影响，跨平台传输安全。
- 所有 `.ps1` 强制 UTF-8 BOM；`.sh` 为 LF（Linux 运行器执行）。请勿改 `.gitattributes` 的换行规则。
- 服务器出网受限会导致 bootstrap 下载失败；可手动把 JRE/Python/MySQL/WinSW 放到 `C:\srtp\tools` 后加 `-SkipJre`/`-SkipPython`/`-SkipMysql` 跳过下载。
