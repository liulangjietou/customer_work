#!/usr/bin/env bash
# 轻量回归：渠道 + 网关 + 若干门禁，不跑全反应堆（省时、适合改文档/脚本后的快验）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ "$(uname -s)" == "Darwin" ]] && /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi

MVN=(mvn -gs scripts/settings-central-direct.xml -s scripts/settings-central-direct.xml)
EXTRA=(-Djacoco.skip=true -Dtest='!RedisSessionPersistenceTest' -Dsurefire.failIfNoSpecifiedTests=false)

echo "=== customer-channel tests ==="
"${MVN[@]}" -pl customer-channel "${EXTRA[@]}" test -q

echo "=== customer-work-gateway tests ==="
"${MVN[@]}" -pl customer-work-gateway "${EXTRA[@]}" test -q

echo "=== starter assembly gate (sample) ==="
"${MVN[@]}" -pl customer-work-starter -Dtest=AgentAssemblyAlignmentTest -Djacoco.skip=true test -q

echo "OK focused tests"