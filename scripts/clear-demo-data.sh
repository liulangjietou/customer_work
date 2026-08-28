#!/bin/bash
# =============================================================================
# 清空建库脚本自带的演示数据，保留系统种子。
#
# 背景：mysql/01-agent-scope-customer-work/customer-work-schema.sql 里带了一批
# 供本地演示用的业务数据——"U-demo-1"、"旗舰款无线降噪耳机"、"退款专员-小赵"
# 这类假订单、假会员、假坐席。本地跑 demo 时它们很有用，上生产就是脏数据：
# 客服智能体会拿它们当真实业务回答问题，报表里也会多出几笔不存在的订单。
#
# **哪些清、哪些留，判据是"系统缺了它会不会坏"**：
#   清：订单/会员/商品/投诉/退款/坐席/知识条目——纯演示内容，业务侧自己会产生真实数据
#   留：配额档位（cw_subject_quota_level，限流按 level_code 查，缺了直接 fail-closed）
#       字典（cw_dict_type / cw_dict_item）、敏感词基础词库（cw_sensitive_word）
#       后台的菜单权限、角色、租户、模型单价、系统工具定义
#
# 对外开放部署（admin.public-deployment.enabled=true）额外清 SQL 查询功能的示例配置：
# 那个功能已被划为内部运维工具、菜单与接口都已下架，留着的配置行没有任何用处。
#
# 用法：
#   scripts/clear-demo-data.sh <客服端库名> <后台库名> [--public]
# 例：
#   scripts/clear-demo-data.sh agent_scope_customer_work customer_admin --public
# 环境变量：
#   MYSQL_HOST(localhost) MYSQL_PORT(3306) MYSQL_USER(root) MYSQL_PASSWORD(root)
#   MYSQL_DOCKER_CONTAINER  设置后改用 docker exec 执行
# =============================================================================
set -euo pipefail

CW_DB="${1:-}"
ADMIN_DB="${2:-}"
PUBLIC_MODE="${3:-}"

if [ -z "${CW_DB}" ] || [ -z "${ADMIN_DB}" ]; then
  echo "用法: $0 <客服端库名> <后台库名> [--public]" >&2
  exit 1
fi

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
CONTAINER="${MYSQL_DOCKER_CONTAINER:-}"

run_sql() {
  if [ -n "${CONTAINER}" ]; then
    docker exec -i "${CONTAINER}" mysql --default-character-set=utf8mb4 \
      -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" -e "$1" 2>&1 | grep -v "Warning" || true
  else
    mysql --default-character-set=utf8mb4 -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" \
      -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" -e "$1"
  fi
}

echo "清理客服端库演示数据：${CW_DB}"
run_sql "
USE \`${CW_DB}\`;
DELETE FROM \`cw_order\`;
DELETE FROM \`cw_refund\`;
DELETE FROM \`cw_complaint\`;
DELETE FROM \`cw_member\`;
DELETE FROM \`cw_product\`;
DELETE FROM \`cw_seat_agent\`;
DELETE FROM \`cw_knowledge\`;
"

if [ "${PUBLIC_MODE}" = "--public" ]; then
  echo "清理后台库的 SQL 查询示例配置（对外部署下该功能已下架）：${ADMIN_DB}"
  run_sql "
USE \`${ADMIN_DB}\`;
DELETE FROM \`sql_field_transform\`;
DELETE FROM \`sql_define_param\`;
DELETE FROM \`sql_define\`;
DELETE FROM \`sql_datasource\`;
"
fi

echo
echo "清理完成。核对——下面每张表都应为 0 行："
run_sql "
SELECT 'cw_order' AS t, COUNT(*) AS c FROM \`${CW_DB}\`.\`cw_order\`
UNION ALL SELECT 'cw_member', COUNT(*) FROM \`${CW_DB}\`.\`cw_member\`
UNION ALL SELECT 'cw_product', COUNT(*) FROM \`${CW_DB}\`.\`cw_product\`
UNION ALL SELECT 'cw_seat_agent', COUNT(*) FROM \`${CW_DB}\`.\`cw_seat_agent\`
UNION ALL SELECT 'cw_knowledge', COUNT(*) FROM \`${CW_DB}\`.\`cw_knowledge\`;
"
echo "下面每张表都应【有】数据（系统种子，缺了会坏）："
run_sql "
SELECT 'cw_subject_quota_level' AS t, COUNT(*) AS c FROM \`${CW_DB}\`.\`cw_subject_quota_level\`
UNION ALL SELECT 'sys_permission(菜单权限)', COUNT(*) FROM \`${ADMIN_DB}\`.\`sys_permission\`
UNION ALL SELECT 'sys_role', COUNT(*) FROM \`${ADMIN_DB}\`.\`sys_role\`
UNION ALL SELECT 'sys_user(admin 种子账号)', COUNT(*) FROM \`${ADMIN_DB}\`.\`sys_user\`;
"
