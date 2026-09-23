#!/usr/bin/env bash
# ============================================================================
#  在服务器上执行 deploy.ps1（含失败自动回滚）
#
#  通过 PowerShell -EncodedCommand（UTF-16LE base64）下发脚本，
#  规避 bash -> ssh -> cmd -> powershell 的多层引号/编码问题。
#
#  需要的环境变量：
#    VERSION    本次发布版本号（必填）
#    ENV_ONLY   true 表示仅同步密钥并重启（可选）
#    COMMIT     短 SHA（可选，写入 release.json）
#    BRANCH     分支名（可选，写入 release.json）
#    RUN_URL    构建运行链接（可选，写入 release.json）
# ============================================================================
set -euo pipefail

: "${VERSION:?缺少 VERSION}"

MODE_ARG=""
if [ "${ENV_ONLY:-false}" = "true" ]; then
  MODE_ARG='-EnvOnly'
fi

COMMIT="${COMMIT:-}"
BRANCH="${BRANCH:-}"
RUN_URL="${RUN_URL:-}"

PS_SCRIPT=$(cat <<PS
\$ErrorActionPreference = 'Stop'
try {
    & 'C:\srtp\scripts\deploy.ps1' -Version '${VERSION}' -EnvFile 'C:\srtp\incoming\secrets.env' -Commit '${COMMIT}' -Branch '${BRANCH}' -RunUrl '${RUN_URL}' ${MODE_ARG}
    if (\$LASTEXITCODE -and \$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }
    exit 0
} catch {
    Write-Host "部署脚本异常: \$_"
    exit 1
}
PS
)
ENC=$(printf '%s' "$PS_SCRIPT" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)

echo "开始部署 ${VERSION} ..."
rsh "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand ${ENC}"
