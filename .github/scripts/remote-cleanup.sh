#!/usr/bin/env bash
# ============================================================================
#  在服务器上清理指定的旧代码目录（仅删除 CLEANUP_PATHS 中列出的路径）
#
#  安全机制：
#    - 只删除显式列出的绝对路径，绝不递归删除整盘
#    - 服务端 PowerShell 内置受保护路径黑名单（C:\ / Windows / Program Files /
#      ProgramData / Users / srtp），命中即跳过，绝不删除系统或部署目录
#    - DRY_RUN=true 时只统计大小、不真正删除（建议先跑一次预览）
#
#  路径列表以 UTF-16LE base64 内嵌进 PowerShell，规避 bash/ssh/powershell
#  多层引号与 Windows 反斜杠冲突（已在本地用 PS 5.1 解析器验证）。
#
#  需要的环境变量：
#    CLEANUP_PATHS  换行分隔的服务器绝对路径列表（必填）
#    DRY_RUN        true(默认，仅预览) / false(真删)
# ============================================================================
set -euo pipefail

: "${CLEANUP_PATHS:?缺少 CLEANUP_PATHS（要清理的服务器路径，每行一个）}"

if [ "${DRY_RUN:-true}" = "true" ]; then
  DRY_REPL='$true'
else
  DRY_REPL='$false'
fi

# 把路径列表 base64（UTF-16LE）后内嵌，彻底规避反斜杠与换行转义问题
PATHS_B64="$(printf '%s' "$CLEANUP_PATHS" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)"

TMP="$(mktemp /tmp/srtp-cleanup.XXXXXX.ps1)"
cat <<'PS' > "$TMP"
$ErrorActionPreference = 'Stop'
$pathsB64 = '__PATHSB64__'
$raw = [System.Text.Encoding]::Unicode.GetString([System.Convert]::FromBase64String($pathsB64))
$paths = @($raw -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
# 受保护路径：只匹配这些目录自身或其子目录，绝不删除
$protected = @('C:\', 'C:\Windows', 'C:\Program Files', 'C:\Program Files (x86)', 'C:\ProgramData', 'C:\Users', 'C:\srtp')
$dry = __DRY__
$items = New-Object System.Collections.ArrayList
foreach ($p in $paths) {
  $obj = [ordered]@{ path = $p; existed = $false; sizeBytes = 0; removed = $false; protected = $false; error = $null }
  try {
    if (-not (Test-Path -LiteralPath $p)) { [void]$items.Add($obj); continue }
    $obj.existed = $true
    $resolved = $p
    try { $resolved = (Resolve-Path -LiteralPath $p -ErrorAction Stop).ProviderPath } catch {}
    $rp = $resolved.TrimEnd('\').ToLower()
    $hit = $false
    foreach ($pr in $protected) {
      $pr2 = $pr.TrimEnd('\').ToLower()
      if ($pr2 -eq 'c:') {
        # 仅保护驱动器根本身，不保护其下所有内容
        if ($rp -eq 'c:') { $hit = $true; break }
      } else {
        if ($rp -eq $pr2 -or $rp.StartsWith($pr2 + '\')) { $hit = $true; break }
      }
    }
    if ($hit) { $obj.protected = $true; $obj.error = '受保护路径，已跳过'; [void]$items.Add($obj); continue }
    $size = (Get-ChildItem -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $size) { $size = 0 }
    $obj.sizeBytes = [int64]$size
    if (-not $dry) {
      Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction Stop
      $obj.removed = $true
    }
  } catch {
    $obj.error = $_.Exception.Message
  }
  [void]$items.Add($obj)
}
$result = [ordered]@{ dry_run = $dry; items = $items.ToArray() }
Write-Output ("===SRTP_CLEANUP_JSON_BEGIN===" + ($result | ConvertTo-Json -Compress -Depth 5) + "===SRTP_CLEANUP_JSON_END===")
PS

# base64 含 '/'，用 @ 作 sed 分隔符避免冲突
sed -i "s@__PATHSB64__@${PATHS_B64}@g" "$TMP"
sed -i "s@__DRY__@${DRY_REPL}@g" "$TMP"

ENC="$(iconv -f UTF-8 -t UTF-16LE "$TMP" | base64 -w0)"
echo "执行服务器清理（DRY_RUN=${DRY_RUN:-true}）..."
OUT="$(rsh "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand ${ENC}" 2>&1)"
echo "$OUT"

JSON="$(echo "$OUT" | sed -n 's/.*===SRTP_CLEANUP_JSON_BEGIN===//; s/===SRTP_CLEANUP_JSON_END===.*//p' | head -1)"
if [ -n "$JSON" ]; then
  echo "$JSON" > cleanup-result.json
  echo "--- 已写入 cleanup-result.json（供摘要步骤读取）---"
else
  echo "::warning::未从服务器返回清理结果 JSON"
fi
rm -f "$TMP"
