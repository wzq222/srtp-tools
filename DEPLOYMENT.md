# SRTP 部署手册（Deployment）

本文件说明如何把本仓库通过 **GitHub Actions** 一键部署到 Windows 服务器（`8.140.194.254`，Windows Server 2022），
以及如何管理密钥、查看状态、回滚版本。

> 仓库为 **Public**，因此任何敏感信息（数据库密码、API Key 等）都**只能**放在
> **GitHub Repository Secrets** 与服务器上 **ACL 锁定的 `.env`** 中，绝不入库。

---

## 0. 架构概览

```
GitHub（push / 手动触发 Actions）
   │  workflow_dispatch: Server Cleanup / Production Release (Promote) / Status / Rollback
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
- 本机不需要 GitHub CLI：文件由 AI 在本仓库生成，你只需 `git push` 并在网页配置 Secrets。

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
| `DB_PASSWORD` | ✅ | MySQL root 密码。**首次 bootstrap 用 `-GenerateDbPassword` 生成后回填此处**（见第 3.4 步） |
| `DB_USERNAME` | ⬜ | 默认 `root` |
| `DB_URL` | ⬜ | 默认已给（`jdbc:mysql://127.0.0.1:3306/srtp_tsp?...`） |
| `APP_API_KEY` | ⬜** | 应用 API Key，建议设置 |
| `JWT_SECRET` | ⬜** | JWT 签名密钥，建议设置 |

> `*` 二选一即可，推荐用密钥（`SSH_PRIVATE_KEY`）更安全。
> `**` 虽非强制，但生产环境强烈建议设置，避免空 Key。

这些值**不会**出现在仓库任何文件里：仅存在于 GitHub Secrets 与服务器 ACL 锁定的 `C:\srtp\shared\.env`。

---

## 3. 服务器首次初始化（一次性，在服务器「远程桌面」以管理员运行）

### 3.1 把 `deploy/` 摆到服务器

在服务器上获取本仓库（已 Public，可直接下载 zip 或 `git clone`），然后：

```powershell
# 在服务器 PowerShell（管理员）中执行
New-Item -ItemType Directory C:\srtp\scripts -Force | Out-Null
Copy-Item <仓库>\deploy\scripts\*            C:\srtp\scripts      -Recurse -Force
Copy-Item <仓库>\deploy\winsw               C:\srtp\scripts\winsw -Recurse -Force
Copy-Item <仓库>\deploy\sql                 C:\srtp\scripts\sql   -Recurse -Force
```

### 3.2 准备 CI 用的 SSH 密钥对（在本机）

```bash
ssh-keygen -t ed25519 -f ~/.ssh/srtp_ci -N ""
# 公钥内容（cat ~/.ssh/srtp_ci.pub） -> 第 3.3 步的 -PublicKey
# 私钥内容（cat ~/.ssh/srtp_ci）      -> GitHub Secret SSH_PRIVATE_KEY
```

### 3.3 启用 OpenSSH Server（服务器管理员）

```powershell
powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\enable-openssh.ps1 `
    -PublicKey "ssh-ed25519 AAAA... 你的CI公钥"
```

- 若只用密码登录，省略 `-PublicKey`，改用 `SSH_PASSWORD` Secret。
- 脚本会安装 OpenSSH Server、设自启、放通防火墙 22，并写入 `administrators_authorized_keys`（修正 ACL）。
- **记得在云控制台安全组放通入方向 `22`**。

### 3.4 运行一次性初始化（服务器管理员）

```powershell
# secrets.env 可先留空，或预填 APP_API_KEY / JWT_SECRET
New-Item -ItemType File C:\srtp\incoming\secrets.env -Force

powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\bootstrap-server.ps1 `
    -EnvFile C:\srtp\incoming\secrets.env -GenerateDbPassword
```

脚本会：下载并安装 JRE17 / Python3.12 / WinSW / MySQL8 → 初始化 `srtp_tsp` 库（来自 `schema.sql`）→
写并 ACL 锁定 `C:\srtp\shared\.env`。

> ⚠️ **屏幕上会打印生成的 MySQL root 密码，只显示这一次。请立即把它填入 GitHub Secret `DB_PASSWORD`。**
> 该密码需与服务器 `.env` 中的 `DB_PASSWORD` 一致，Spring Boot 才能连上 MySQL。
> 如果你更希望自己定密码：在 `secrets.env` 里先写好 `DB_PASSWORD=你的密码`，然后**不带** `-GenerateDbPassword` 运行，
> 并把同一个值填到 Secret `DB_PASSWORD`。

### 3.5 验证初始化

```powershell
powershell -ExecutionPolicy Bypass -File C:\srtp\scripts\status.ps1
```

（此时尚未部署应用，`current_version` 为空属正常。）

---

## 4. 触发部署（GitHub Actions）

仓库推送后，在 **Actions** 页面选择对应工作流 → **Run workflow**：

| 工作流 | 用途 | 关键输入 |
| --- | --- | --- |
| **Server Cleanup (清理旧代码)** | 部署前清理服务器上以前的无关代码 | `paths`（要删的绝对路径，每行一个）、`dry_run`（先预览再真删） |
| **Production Release (Promote)** | 完整构建并部署（或仅同步密钥） | `env_only`（仅同步密钥并重启）、`version_suffix`、`skip_tests`、`cleanup_paths`（可选，部署前顺带清理） |
| **Production Status** | 只读巡检，输出 JSON 到 Summary | `fail_if_down`（不健康则失败）、`app_port` |
| **Production Rollback** | 回滚版本 | `target`（`previous` 或版本号）、`list_only` |

- **部署前清理旧代码**：先运行 **Server Cleanup**，`dry_run` 先设为 `true` 预览将要删除的内容（含每个路径的大小），确认无误后再设为 `false` 真正删除。该工作流**只删除你列出的路径**，并内置受保护路径黑名单（`C:\` 根、`C:\Windows`、`C:\Program Files`、`C:\ProgramData`、`C:\Users`、`C:\srtp`），命中即跳过，绝不会误删系统或现有部署目录。详见第 4.5 节。

- **首次部署**：直接运行 Promote（默认完整部署）。`deploy.ps1` 会把 jar 安置到 `releases/<version>`、注册并启动服务、做健康检查；失败会自动回滚到上一版本。
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
