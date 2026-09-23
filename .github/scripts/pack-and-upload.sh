#!/usr/bin/env bash
# ============================================================================
#  组装并上传发布包到服务器用户主目录
#
#  打包结构（tar.gz 根）：
#    scripts/            服务器端 PowerShell 部署脚本（含 winsw/ 与 sql/ 子目录）
#    app/                发布载荷：srtp-server.jar + python/ + srtp-app-source/
#    secrets.env         从 Repository Secrets 渲染的密钥文件
#
#  为什么用 tar.gz 单包上传：
#    1) 一次传输，避免逐个 scp 的 Windows 远端路径解析问题
#    2) 打包/解包两端都保留 UTF-8 文件名（算法核心文件名含中文）
# ============================================================================
set -euo pipefail

: "${SSH_HOST:?}" "${SSH_USER:?}"
ENV_ONLY="${ENV_ONLY:-false}"

rm -rf transfer/tarroot
mkdir -p transfer/tarroot/scripts/winsw transfer/tarroot/scripts/sql

cp -r deploy/scripts/. transfer/tarroot/scripts/
cp -r deploy/winsw/.   transfer/tarroot/scripts/winsw/
cp -r deploy/sql/.     transfer/tarroot/scripts/sql/
cp transfer/secrets.env transfer/tarroot/secrets.env

if [ "$ENV_ONLY" != "true" ]; then
  if [ -d staging/app ]; then
    cp -r staging/app transfer/tarroot/app
  else
    echo "::error::未找到发布载荷 staging/app"
    exit 1
  fi
else
  echo "env_only 模式：本次不携带发布载荷"
fi

echo "--- 待上传内容 ---"
find transfer/tarroot -type f | sort

tar -czf transfer/srtp-release.tar.gz -C transfer/tarroot .

# 只给文件名，不带远端路径：Windows OpenSSH 的 scp 对 /c:/ 形式路径解析易出错
rcp transfer/srtp-release.tar.gz "${SSH_USER}@${SSH_HOST}:srtp-release.tar.gz"

echo "上传完成：$(du -h transfer/srtp-release.tar.gz | cut -f1)"
