#!/bin/bash
# =============================================================================
# 按版本序执行 mysql/02-customer-admin/ 的全部脚本，建起完整的 customer_admin 结构。
#
# 为什么需要这个脚本，而不是 `for f in mysql/02-customer-admin/*.sql`：
#   那个目录的约定是"文件名前缀 = 版本号，排序即执行序"，但 shell glob 与 ls 用的是
#   **字典序**，版本号进入三位数之后两者就分叉了：
#       ... 09-V9, 10-V10, 100-V100, 101-V101, 11-V11, ... 99-V99
#   V100/V101 会跑在 V11 之前——那时它们依赖的表和列还不存在。实测后果：
#   V101 直接报 Unknown column 'r.control_plane'（V63 才加的列），
#   而 V100 更隐蔽，它逐表守卫、表不存在就跳过，于是**静默什么都没做**，
#   建出来的库排序规则始终没对齐，且没有任何报错。
#
#   这里用 `sort -V`（版本号排序）取代字典序，让执行顺序与版本号严格一致。
#
# 用法：
#   scripts/apply-admin-schema-mirror.sh [目标库名]
# 环境变量：
#   MYSQL_HOST(localhost) MYSQL_PORT(3306) MYSQL_USER(root) MYSQL_PASSWORD(root)
#   MYSQL_DOCKER_CONTAINER  设置后改用 docker exec 执行（本机 MySQL 跑在容器里时用）
#
# 字符集：一律 `-e "source <file>"` 而非 stdin 管道。走 stdin 时客户端字符集会回退 latin1，
# 把建表语句里内联的中文 COMMENT 字节级写坏——是显示正常、数据已错的那种坏。
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
MIRROR_DIR="mysql/02-customer-admin"
DATABASE="${1:-customer_admin}"

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
CONTAINER="${MYSQL_DOCKER_CONTAINER:-}"

if [ ! -d "${MIRROR_DIR}" ]; then
  echo "错误：找不到 ${MIRROR_DIR}" >&2
  exit 1
fi

# 00-create-database.sql 建的是固定库名 customer_admin，与本脚本的目标库参数冲突，
# 故跳过它、由下面这条按参数建库。
run_sql() {
  if [ -n "${CONTAINER}" ]; then
    docker exec -i "${CONTAINER}" mysql --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "$@"
  else
    mysql --default-character-set=utf8mb4 -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" \
      -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "$@"
  fi
}

echo "目标库：${DATABASE}"
run_sql -e "CREATE DATABASE IF NOT EXISTS \`${DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

applied=0
# sort -V 按版本号排序：09 < 10 < 11 < ... < 99 < 100 < 101，与文件名里的版本号一致
for f in $(ls "${MIRROR_DIR}"/*.sql | sort -V); do
  base="$(basename "${f}")"
  if [ "${base}" = "00-create-database.sql" ]; then
    continue
  fi
  echo "  applying ${base}"
  if [ -n "${CONTAINER}" ]; then
    docker cp "${f}" "${CONTAINER}:/tmp/${base}" >/dev/null
    run_sql "${DATABASE}" -e "source /tmp/${base}"
    docker exec "${CONTAINER}" rm -f "/tmp/${base}"
  else
    run_sql "${DATABASE}" -e "source ${f}"
  fi
  applied=$((applied + 1))
done

echo "完成：${applied} 个脚本已按版本序应用到 ${DATABASE}"
