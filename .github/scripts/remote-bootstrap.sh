#!/usr/bin/env bash
# ============================================================================
#  在服务器上执行 bootstrap-server.ps1（一次性初始化）
#
#  完成：OpenSSH 校验 -> JRE17 -> Python3.12 -> WinSW -> MySQL8
#        -> 创建 C:\srtp 布局 -> 写入 ACL 锁定的 .env -> 渲染服务定义
#
#  需要的环境变量（均由 bootstrap.yml 注入）：
#    SKIP_JRE / SKIP_PYTHON / SKIP_MYSQL   'true' 表示跳过对应组件
#    APP_PORT                              应用端口（可选）
#    MYSQL_DOWNLOAD_URL                    MySQL 8 zip 下载地址（可选）
#    PYTHON_VERSION                        Python 版本（可选）
#
#  ⚠️ 刻意不暴露 bootstrap 的 -GenerateDbPassword：
#     本仓库可能是公开仓库，Actions 日志对所有人可见。
#     自动生成的密码不属于 Secrets，不会被日志脱敏，会明文泄露。
#     因此 DB_PASSWORD 必须先配到 Repository Secrets，由 secrets.env 下发。
#
#  通过 PowerShell -EncodedCommand（UTF-16LE base64）下发，
#  规避 bash -> ssh -> cmd -> powershell 的多层引号/编码问题。
# ============================================================================
set -euo pipefail

ENV_FILE='C:\srtp\incoming\secrets.env'

ARGS="-EnvFile '${ENV_FILE}'"

if [ "${SKIP_JRE:-false}" = "true" ]; then
  ARGS="${ARGS} -SkipJre"
fi
if [ "${SKIP_PYTHON:-false}" = "true" ]; then
  ARGS="${ARGS} -SkipPython"
fi
if [ "${SKIP_MYSQL:-false}" = "true" ]; then
  ARGS="${ARGS} -SkipMysql"
fi
if [ -n "${APP_PORT:-}" ]; then
  ARGS="${ARGS} -AppPort ${APP_PORT}"
fi
if [ -n "${MYSQL_DOWNLOAD_URL:-}" ]; then
  ARGS="${ARGS} -MysqlDownloadUrl '${MYSQL_DOWNLOAD_URL}'"
fi
if [ -n "${PYTHON_VERSION:-}" ]; then
  ARGS="${ARGS} -PythonVersion '${PYTHON_VERSION}'"
fi

echo "bootstrap 参数：${ARGS}"

PS_SCRIPT=$(cat <<PS
\$ErrorActionPreference = 'Stop'
Write-Host '=========================================================='
Write-Host ' SRTP 服务器一次性初始化 (bootstrap)'
Write-Host '=========================================================='
try {
    & 'C:\srtp\scripts\bootstrap-server.ps1' ${ARGS}
    if (\$LASTEXITCODE -and \$LASTEXITCODE -ne 0) {
        Write-Host "BOOTSTRAP_FAILED 退出码 \$LASTEXITCODE"
        exit \$LASTEXITCODE
    }
    Write-Host 'BOOTSTRAP_OK'
    exit 0
} catch {
    Write-Host "BOOTSTRAP_EXCEPTION: \$_"
    exit 1
}
PS
)

ENC=$(printf '%s' "$PS_SCRIPT" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)

# 初始化会下载 JRE / Python / MySQL 三个安装包（合计约 300MB），
# 期间日志可能长时间静默，依赖 setup-ssh.sh 里放宽的 ServerAliveCountMax 保活。
rsh "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand ${ENC}"

echo "服务器初始化流程执行完毕"
