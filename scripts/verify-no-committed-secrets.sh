#!/usr/bin/env bash
set -euo pipefail

# 只扫描当前提交的受 Git 管理文本，避免 node_modules/target 与本地未跟踪文件造成噪音。
# 私钥 PEM 在证书工具测试里是刻意生成的固定测试夹具，因此本门禁聚焦可直接调用云服务的凭据形态。
patterns=(
  'AKIA[0-9A-Z]{16}'
  'gh[pousr]_[A-Za-z0-9]{36,}'
  'sk-[A-Za-z0-9]{20,}'
  'xox[baprs]-[A-Za-z0-9-]{20,}'
)

found=0
for pattern in "${patterns[@]}"; do
  if git grep -InE -- "$pattern" -- ':!scripts/verify-no-committed-secrets.sh'; then
    found=1
  fi
done

if [[ "$found" -ne 0 ]]; then
  echo "Potential committed credential detected. Remove it and rotate the credential." >&2
  exit 1
fi

echo "No committed cloud credential patterns detected."
