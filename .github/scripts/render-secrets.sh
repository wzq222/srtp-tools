#!/usr/bin/env bash
# ============================================================================
#  从 GitHub Repository Secrets 渲染服务器端密钥文件 transfer/secrets.env
#
#  这些密钥【不会】出现在仓库里，只存在于：
#    GitHub Secrets（写入后不可查看） -> Actions 运行时内存 -> 服务器 ACL 锁定的 .env
#  随后由 deploy.ps1 注入为服务进程的环境变量。
#
#  需要新增密钥时，在 add_kv 列表里加一行，并在 DEPLOYMENT.md 里登记。
# ============================================================================
set -euo pipefail

: "${DB_PASSWORD:?缺少 Secret: DB_PASSWORD}"

mkdir -p transfer
out="transfer/secrets.env"
: > "$out"

# 只在值非空时写入，避免把服务器上的既有配置覆盖成空
add_kv() {
  local key="$1" value="${2:-}"
  if [ -n "$value" ]; then
    printf '%s=%s\n' "$key" "$value" >> "$out"
  fi
}

add_kv DB_PASSWORD "$DB_PASSWORD"
add_kv DB_USERNAME "${DB_USERNAME:-}"
add_kv DB_URL      "${DB_URL:-}"
add_kv APP_API_KEY "${APP_API_KEY:-}"
add_kv JWT_SECRET  "${JWT_SECRET:-}"

echo "已生成 $out，包含以下键："
cut -d= -f1 "$out"
echo "（值不会打印到日志）"
