#!/usr/bin/env bash
# ============================================================================
#  在服务器上解包，并把内容摆放到正确位置
#
#    脚本  -> C:\srtp\scripts     （每次发布都同步，保证与仓库一致）
#    载荷  -> C:\srtp\incoming\<版本>
#    密钥  -> C:\srtp\incoming\secrets.env
#
#  通过 PowerShell -EncodedCommand（UTF-16LE base64）下发，
#  彻底规避 bash -> ssh -> cmd -> powershell 的多层引号/编码问题。
# ============================================================================
set -euo pipefail

: "${VERSION:?缺少 VERSION}"

PS_SCRIPT=$(cat <<'PS'
$ErrorActionPreference = 'Stop'

if (-not $env:SRTP_VERSION) { throw '未收到 SRTP_VERSION' }
$version = $env:SRTP_VERSION

$tar = Join-Path $env:USERPROFILE 'srtp-release.tar.gz'
if (-not (Test-Path $tar)) { throw "未找到上传的发布包: $tar" }

$stage = 'C:\srtp\incoming\_stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory $stage -Force | Out-Null
tar -xzf $tar -C $stage
Write-Host "已解包到 $stage"

# 1) 服务器端部署脚本
if (-not (Test-Path 'C:\srtp\scripts')) {
    New-Item -ItemType Directory 'C:\srtp\scripts' -Force | Out-Null
}
Copy-Item "$stage\scripts\*" 'C:\srtp\scripts' -Recurse -Force
Write-Host "部署脚本已同步到 C:\srtp\scripts"

# 2) 发布载荷
$payload = Join-Path 'C:\srtp\incoming' $version
if (Test-Path "$stage\app") {
    if (Test-Path $payload) { Remove-Item $payload -Recurse -Force }
    New-Item -ItemType Directory $payload -Force | Out-Null
    Copy-Item "$stage\app" $payload -Recurse -Force
    Write-Host "发布载荷已就位: $payload"
} else {
    Write-Host '本次未携带发布载荷（env_only 模式）'
}

# 3) 密钥文件
if (Test-Path "$stage\secrets.env") {
    Copy-Item "$stage\secrets.env" 'C:\srtp\incoming\secrets.env' -Force
    Write-Host '密钥文件已就位: C:\srtp\incoming\secrets.env'
}

Remove-Item $tar -Force -ErrorAction SilentlyContinue
Write-Host 'UNPACK_OK'
PS
)

ENC=$(printf '%s' "$PS_SCRIPT" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)

# 版本号已在工作流中做过字符集白名单校验，可安全拼进 cmd 的 set
rsh "set SRTP_VERSION=${VERSION}&& powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand ${ENC}"

echo "服务器端解包完成"
