# SRTP —— TSP 算法平台

面向"旅行商问题（TSP）求解"的算法平台：前端（Vue 3）提供交互界面，后端（Spring Boot 3）提供 REST API 与任务调度，
Python 算法桥接层调用内置的多种 TSP 求解算法（含 SA 等）。支持通过 **GitHub Actions** 一键部署到 Windows 服务器。

## 目录结构

```
srtp-web/            前端（Vue 3 + Vite）
srtp-server/         后端（Spring Boot 3.2 + Maven）
  ├─ src/main/resources/static/   前端构建产物（由 CI 生成，不入库）
  ├─ python/algorithm_bridge.py   Python 算法桥接
  └─ start.bat / local-secrets.bat.example   本地启动与密钥模板
srtp-app-source/     TSP 算法核心（含四算法整合优化.py）
deploy/              服务器端部署脚本（PowerShell）+ WinSW 模板 + 建表 SQL
.github/workflows/   GitHub Actions：promote / status / rollback
.github/scripts/     CI 侧辅助脚本（SSH 封装、密钥渲染、打包上传、解包、远程部署）
DEPLOYMENT.md        服务器初始化与部署手册（必读）
```

## 技术栈

- 前端：Vue 3 + Vite + Pinia
- 后端：Spring Boot 3.2、Java 17、Maven
- 算法：Python 3.12（仅标准库，numpy 可选）、通过 `algorithm_bridge.py` 桥接
- 数据库：MySQL 8
- 部署：GitHub Actions → SSH → Windows Server（WinSW 包装为服务）

## 本地开发

```bash
# 1) 前端
cd srtp-web && npm install && npm run dev

# 2) 后端（先按 local-secrets.bat.example 复制为 local-secrets.bat 填好密钥，再启动）
cd srtp-server
cp local-secrets.bat.example local-secrets.bat   # 编辑填入 DB_PASSWORD 等
.\start.bat
```

> 密钥不要提交：`.env`、`local-secrets.bat`、`*.pem`、`id_rsa` 等已在 `.gitignore` 中排除。

## 部署

详见 **[DEPLOYMENT.md](./DEPLOYMENT.md)**。首次使用顺序（Actions 页面点按钮即可）：

1. **Server Bootstrap (首次初始化)** —— 只跑一次，在服务器上装 JRE17 / Python3.12 / WinSW / MySQL8，
   建库、写密钥、注册自启服务（跑之前需先在服务器上开启 OpenSSH，见手册第 3.1 节）。
2. **Server Cleanup (清理旧代码)** —— 可选，清理服务器上历史遗留的无关目录（默认 `C:\deploy`）。
3. **Production Release (Promote)** —— 构建并发布，失败自动回滚。

日常运维：**Production Status** 巡检、**Production Rollback** 回滚。

## 敏感信息说明

所有密钥（数据库密码、API Key、JWT 密钥、SSH 凭据）只存在于 **GitHub Repository Secrets**
与服务器上 **ACL 锁定的 `C:\srtp\shared\.env`**，仓库中不留任何明文。
