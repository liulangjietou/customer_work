#!/bin/bash
# =============================================================================
# 重新生成两个业务库的全量表结构快照。
#
#   mysql/schema-snapshot/agent_scope_customer_work.sql   客服端业务库
#   mysql/schema-snapshot/customer_admin.sql              后台管理库
#
# 做法：各起一个带随机后缀的临时空库，跑完该库的全部 Flyway 迁移后逐表导出，最后删库。
# 不从开发机的长期业务库导出——那个库被并行分支的迁移反复试跑过，沉积的结构与迁移产物无法区分。
#
# 与门禁同源：生成走的就是 CustomerWorkSchemaSnapshotTest / CustomerAdminSchemaSnapshotTest
# 这两个测试的写入模式，日常跑测试时它们会把快照与迁移产物逐字比对，漏刷新会直接红。
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

# shell 默认 java 常年是 1.8，必须显式切 17
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"17'; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
echo "JAVA_HOME=${JAVA_HOME}"

# scripts/ 下那份写死了本机 localRepository，换台机器会直接报 Could not create local repository；
# 此时回落到仓库根的同名文件（走默认 ~/.m2/repository）。
SETTINGS="${REPO_ROOT}/scripts/settings-central-direct.xml"
LOCAL_REPO="$(sed -n 's:.*<localRepository>\(.*\)</localRepository>.*:\1:p' "${SETTINGS}" | head -1)"
if [ -n "${LOCAL_REPO}" ] && [ ! -d "${LOCAL_REPO}" ]; then
  SETTINGS="${REPO_ROOT}/settings-central-direct.xml"
  echo "本机无 ${LOCAL_REPO}，改用 ${SETTINGS}"
fi

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"

# MySQL 不可达时两个测试都会 assumeTrue 跳过，脚本会「成功」退出而快照一个字没变——
# 这种静默失效比直接报错难查得多，所以先探一次。
if ! (exec 3<>"/dev/tcp/${MYSQL_HOST}/${MYSQL_PORT}") 2>/dev/null; then
  echo "错误：MySQL ${MYSQL_HOST}:${MYSQL_PORT} 不可达，无法生成快照。" >&2
  echo "      本机可用 docker start mysql_db_low_case 启动。" >&2
  exit 1
fi
echo "MySQL ${MYSQL_HOST}:${MYSQL_PORT} 可达"

# admin 侧测试账号口径与 run-admin-server-dev.sh 保持一致
export ADMIN_MYSQL_PASSWORD="${ADMIN_MYSQL_PASSWORD:-root}"

mvn -gs "${SETTINGS}" -s "${SETTINGS}" \
  -pl customer-work-starter,customer-admin-server -am \
  test \
  -Dtest='CustomerWorkSchemaSnapshotTest,CustomerAdminSchemaSnapshotTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dschema.snapshot.write=true \
  -Djacoco.skip=true

SNAPSHOTS=(
  "mysql/schema-snapshot/agent_scope_customer_work.sql"
  "mysql/schema-snapshot/customer_admin.sql"
)
for snapshot in "${SNAPSHOTS[@]}"; do
  if [ ! -s "${REPO_ROOT}/${snapshot}" ]; then
    echo "错误：${snapshot} 未生成或为空，请检查上面的测试输出。" >&2
    exit 1
  fi
done

echo ""
echo "快照已刷新："
for snapshot in "${SNAPSHOTS[@]}"; do
  printf '  %-52s %s 张表\n' "${snapshot}" \
    "$(grep -c '^CREATE TABLE ' "${REPO_ROOT}/${snapshot}")"
done
echo ""
git -C "${REPO_ROOT}" --no-pager diff --stat -- mysql/schema-snapshot/ || true
