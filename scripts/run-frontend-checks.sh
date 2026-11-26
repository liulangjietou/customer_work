#!/usr/bin/env bash
# 两个前端的类型检查 + 单元测试（不跑 Playwright e2e，避免依赖 8082/5174 已启动）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

run_web() {
  local dir="$1"
  local name="$2"
  echo "=== $name ==="
  cd "$ROOT/$dir"
  if [[ ! -d node_modules ]]; then
    echo "Installing dependencies in $dir ..."
    npm install --no-audit --no-fund
  fi
  npm run test:unit
  npm run build
}

run_web customer-admin-web "customer-admin-web"
run_web customer-work-app "customer-work-app (H5)"

echo "OK frontend checks"