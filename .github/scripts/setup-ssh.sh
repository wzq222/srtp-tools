#!/usr/bin/env bash
# ============================================================================
#  建立到目标服务器的 SSH 封装命令：rsh / rcp
#
#  优先使用密钥认证（SSH_KEY），否则回退到密码认证（SSH_PASSWORD，需 sshpass）。
#  生成的两个封装脚本会从环境变量读取 SSH_HOST / SSH_USER / SSH_PORT，
#  所以调用方必须把它们导出到 $GITHUB_ENV。
# ============================================================================
set -euo pipefail

: "${SSH_HOST:?缺少 SSH_HOST（Repository Secret）}"
: "${SSH_USER:?缺少 SSH_USER（Repository Secret）}"
export SSH_PORT="${SSH_PORT:-22}"

mkdir -p "$HOME/.ssh"
chmod 700 "$HOME/.ssh"

if [ -n "${SSH_KEY:-}" ]; then
  printf '%s\n' "$SSH_KEY" > "$HOME/.ssh/deploy_key"
  chmod 600 "$HOME/.ssh/deploy_key"

  cat > /usr/local/bin/rsh <<'EOF'
#!/usr/bin/env bash
# ServerAliveCountMax 放宽到 20（=10 分钟静默容忍）：
# bootstrap 下载 JRE/Python/MySQL 约 300MB，期间远端可能长时间无输出
exec ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=20 \
  -p "${SSH_PORT}" -i "${HOME}/.ssh/deploy_key" "${SSH_USER}@${SSH_HOST}" "$@"
EOF

  cat > /usr/local/bin/rcp <<'EOF'
#!/usr/bin/env bash
exec scp -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 \
  -P "${SSH_PORT}" -i "${HOME}/.ssh/deploy_key" "$@"
EOF

  echo "SSH 认证方式：密钥（SSH_PRIVATE_KEY）"
else
  : "${SSH_PASSWORD:?必须提供 SSH_PRIVATE_KEY 或 SSH_PASSWORD 之一}"

  sudo apt-get update -qq
  sudo apt-get install -y -qq sshpass

  cat > /usr/local/bin/rsh <<'EOF'
#!/usr/bin/env bash
exec sshpass -e ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=20 \
  -o PreferredAuthentications=password -o PubkeyAuthentication=no \
  -p "${SSH_PORT}" "${SSH_USER}@${SSH_HOST}" "$@"
EOF

  cat > /usr/local/bin/rcp <<'EOF'
#!/usr/bin/env bash
exec sshpass -e scp -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 \
  -o PreferredAuthentications=password -o PubkeyAuthentication=no \
  -P "${SSH_PORT}" "$@"
EOF

  # sshpass -e 从 SSHPASS 环境变量取密码，需导出到后续所有步骤
  echo "SSHPASS=${SSH_PASSWORD}" >> "$GITHUB_ENV"
  echo "SSH 认证方式：密码（SSH_PASSWORD）"
fi

chmod +x /usr/local/bin/rsh /usr/local/bin/rcp

# 供后续步骤使用的连接参数
{
  echo "SSH_HOST=${SSH_HOST}"
  echo "SSH_USER=${SSH_USER}"
  echo "SSH_PORT=${SSH_PORT}"
} >> "$GITHUB_ENV"

echo "--- 连通性测试 ---"
rsh "echo SSH_OK"
echo "SSH 连接就绪"
