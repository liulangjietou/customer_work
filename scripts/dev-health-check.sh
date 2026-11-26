#!/usr/bin/env bash
# 本机/CI 开发依赖连通性快检：不启动业务，只探测端口与关键环境变量。
# 用法: ./scripts/dev-health-check.sh [--strict]
#   --strict  任一必需项失败则以非 0 退出（默认仅打印，始终 0）

set -euo pipefail

STRICT=false
if [[ "${1:-}" == "--strict" ]]; then
  STRICT=true
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

check_tcp() {
  local name="$1"
  local host="$2"
  local port="$3"
  local required="${4:-true}"
  if command -v nc >/dev/null 2>&1; then
    if nc -z -w 2 "$host" "$port" 2>/dev/null; then
      echo "OK   $name ($host:$port)"
    else
      echo "FAIL $name ($host:$port) unreachable"
      if [[ "$required" == "true" ]]; then
        FAIL=1
      else
        echo "     (optional)"
      fi
    fi
  elif command -v bash >/dev/null 2>&1; then
    if timeout 2 bash -c "echo >/dev/tcp/$host/$port" 2>/dev/null; then
      echo "OK   $name ($host:$port)"
    else
      echo "FAIL $name ($host:$port) unreachable"
      [[ "$required" == "true" ]] && FAIL=1 || echo "     (optional)"
    fi
  else
    echo "SKIP $name (no nc or /dev/tcp)"
  fi
}

echo "=== customer-work dev health check ==="
echo "repo: $ROOT"
echo ""

# Java（优先 JAVA_HOME/bin/java，避免 PATH 与 JAVA_HOME 不一致）
JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
fi
if command -v "$JAVA_BIN" >/dev/null 2>&1; then
  ver="$("$JAVA_BIN" -version 2>&1 | head -1)"
  if "$JAVA_BIN" -version 2>&1 | grep -qE 'version "1[7-9]|version "[2-9][0-9]'; then
    echo "OK   Java 17+ ($ver)"
  else
    echo "FAIL Java: need 17+, got: $ver"
    echo "     tip: export JAVA_HOME=\$(/usr/libexec/java_home -v 17)"
    if [[ "$(uname -s)" == "Darwin" ]] && /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
      echo "     found: JAVA_HOME=\$(/usr/libexec/java_home -v 17)"
    fi
    FAIL=1
  fi
else
  echo "FAIL java not in PATH"
  FAIL=1
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "     JAVA_HOME=$JAVA_HOME"
fi

echo ""
echo "--- TCP (defaults match CLAUDE.local / docker-compose) ---"
check_tcp "MySQL" "127.0.0.1" "3306" true
check_tcp "Redis" "127.0.0.1" "6379" true
check_tcp "MinIO API" "127.0.0.1" "9000" false
check_tcp "Nacos" "127.0.0.1" "8848" false
check_tcp "PaddleOCR" "127.0.0.1" "8868" false
check_tcp "XXL-JOB admin" "127.0.0.1" "8088" false

echo ""
echo "--- Maven ---"
if command -v mvn >/dev/null 2>&1; then
  echo "OK   mvn $(mvn -v 2>/dev/null | head -1)"
  echo "     full test: export JAVA_HOME=...; mvn -gs scripts/settings-central-direct.xml -s scripts/settings-central-direct.xml clean test -Djacoco.skip=true"
  echo "     admin tests: export ADMIN_MYSQL_PASSWORD=root"
else
  echo "FAIL mvn not in PATH"
  FAIL=1
fi

echo ""
echo "--- Docker (optional) ---"
if command -v docker >/dev/null 2>&1; then
  for c in mysql_db_low_case redis minio-server nacos; do
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$c"; then
      echo "OK   container $c running"
    else
      echo "---- container $c not running (optional)"
    fi
  done
else
  echo "SKIP docker not installed"
fi

echo ""
if [[ "$FAIL" -eq 1 ]]; then
  echo "Result: FAILED (see FAIL lines above)"
  echo "Docs: docs/本地排障速查.md · docs/新人必读.md"
  $STRICT && exit 1 || exit 0
fi
echo "Result: all required checks passed (optional skips are informational)"
exit 0