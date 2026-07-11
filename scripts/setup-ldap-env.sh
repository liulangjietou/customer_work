#!/bin/bash
#
# LDAP 环境变量永久导入脚本
# 用法：
#   1. 修改下面三个变量的值为你企业的实际地址
#   2. chmod +x scripts/setup-ldap-env.sh
#   3. ./scripts/setup-ldap-env.sh
#   4. source ~/.zshrc  （或新开终端窗口自动生效）
#

# ===== 按需修改以下三行 替换成你自己的url和domian_suffix =====
LDAP_URL="ldap://domain:123"
LDAP_DOMAIN_SUFFIX="@com.cn"
LDAP_ENABLED="true"
# ============================

ZSHRC="$HOME/.zshrc"
MARKER_BEGIN="# >>> customer-work LDAP env >>>"
MARKER_END="# <<< customer-work LDAP env <<<"

# 先移除旧的块（避免重复追加）
if grep -q "$MARKER_BEGIN" "$ZSHRC" 2>/dev/null; then
  echo "检测到已有 LDAP 环境变量配置，将更新..."
  # 用 sed 删除标记之间的内容
  sed -i '' "/$MARKER_BEGIN/,/$MARKER_END/d" "$ZSHRC"
fi

# 追加新配置
cat >> "$ZSHRC" << EOF
$MARKER_BEGIN
export ADMIN_LDAP_URL="$LDAP_URL"
export ADMIN_LDAP_DOMAIN_SUFFIX="$LDAP_DOMAIN_SUFFIX"
export ADMIN_LDAP_ENABLED="$LDAP_ENABLED"
$MARKER_END
EOF

echo "✅ LDAP 环境变量已写入 ~/.zshrc"
echo ""
echo "  ADMIN_LDAP_URL=$LDAP_URL"
echo "  ADMIN_LDAP_DOMAIN_SUFFIX=$LDAP_DOMAIN_SUFFIX"
echo "  ADMIN_LDAP_ENABLED=$LDAP_ENABLED"
echo ""
echo "执行以下命令立即生效（或新开终端窗口）："
echo "  source ~/.zshrc"
