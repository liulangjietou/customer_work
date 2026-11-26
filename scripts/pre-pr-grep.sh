#!/usr/bin/env bash
# 提交 PR 前人工速扫：不替代 CI，只抓高频复发模式。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FAIL=0
warn() { echo "WARN $*"; }
fail() { echo "FAIL $*"; FAIL=1; }

echo "=== pre-pr grep ==="

# 1. 新建 Agent 未装配治理（粗筛，精检见 AgentAssemblyAlignmentTest）
while IFS= read -r f; do
  if grep -q 'ReActAgent\.builder()' "$f" && ! grep -q 'governanceAssembler\.applyTo' "$f"; then
    warn "ReActAgent.builder in $f — confirm governance via applyTo or delegated factory (see AgentAssemblyAlignmentTest)"
  fi
done < <(find customer-work-starter customer-work-app-server customer-admin-server customer-channel \
  -name '*.java' -path '*/src/main/*' 2>/dev/null)

# 2. admin 直接引用 cw Mapper（应走 Facade，粗筛）
if rg 'com\.richard\.fyoung\.customerwork\.(mapper|persistence)' customer-admin-server/src/main/java 2>/dev/null; then
  warn "admin imports customerwork persistence — must go through CustomerWorkFacade"
fi

# 3. 迁移写死库名
if rg -q 'INSERT INTO `customer_admin`' db customer-admin-server/src/main/resources db/customerwork 2>/dev/null; then
  fail "migration with hard-coded database name"
fi

# 4. 明文密钥片段（极端粗筛，仅 main 源码）
if rg -i '(password|secret|apikey)\s*=\s*["\x27][^"\x27]{8,}' \
  --glob '!**/target/**' --glob '!**/*Test*' \
  customer-work-starter/src/main customer-work-app-server/src/main \
  customer-admin-server/src/main customer-channel/src/main 2>/dev/null; then
  warn "possible hard-coded secret — review manually"
fi

if [[ "$FAIL" -eq 1 ]]; then
  echo "Result: FAILED"
  exit 1
fi
echo "Result: OK (warnings are informational)"
exit 0