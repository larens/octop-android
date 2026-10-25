#!/usr/bin/env bash
# 生成 Android 签名用的 keystore，并把口令写进 keystore.properties（两者都不入 git）。
#
# 用法：
#   ./scripts/gen-keystore.sh                 # 交互式输入口令
#   ./scripts/gen-keystore.sh --non-interactive  # 由环境变量或随机口令生成（CI 用）
#
# 产出：
#   keystore/octop-release.jks
#   keystore.properties
#
# 注意：keystore 一旦丢失，已发布的 APK 将无法再覆盖升级。请自行备份。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

KEYSTORE_DIR="$ROOT_DIR/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/octop-release.jks"
PROPS_FILE="$ROOT_DIR/keystore.properties"
ALIAS="${KEY_ALIAS:-octop}"

NON_INTERACTIVE=0
if [[ "${1:-}" == "--non-interactive" ]]; then
  NON_INTERACTIVE=1
fi

if [[ -f "$KEYSTORE_FILE" ]]; then
  echo "keystore 已存在，跳过生成：$KEYSTORE_FILE"
  echo "（如需重建，请先手动删除该文件）"
  exit 0
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "找不到 keytool，请先安装 JDK 17（macOS: brew install openjdk@17）" >&2
  exit 1
fi

mkdir -p "$KEYSTORE_DIR"

if [[ "$NON_INTERACTIVE" == "1" ]]; then
  : "${KEYSTORE_PASSWORD:?非交互模式需要设置 KEYSTORE_PASSWORD}"
  KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
  DNAME="${KEY_DNAME:-CN=Octop Android, OU=Mobile, O=Octop, L=Unknown, ST=Unknown, C=CN}"
else
  read -r -s -p "设置 keystore 口令（至少 6 位）: " KEYSTORE_PASSWORD; echo
  read -r -s -p "再次确认 keystore 口令: " KEYSTORE_PASSWORD_CONFIRM; echo
  if [[ "$KEYSTORE_PASSWORD" != "$KEYSTORE_PASSWORD_CONFIRM" ]]; then
    echo "两次口令不一致" >&2
    exit 1
  fi
  read -r -s -p "设置 key 口令（回车则与 keystore 口令相同）: " KEY_PASSWORD; echo
  KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
  read -r -p "证书持有者信息 [CN=Octop Android, OU=Mobile, O=Octop, L=Unknown, ST=Unknown, C=CN]: " DNAME
  DNAME="${DNAME:-CN=Octop Android, OU=Mobile, O=Octop, L=Unknown, ST=Unknown, C=CN}"
fi

keytool -genkeypair \
  -keystore "$KEYSTORE_FILE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "$DNAME"

cat > "$PROPS_FILE" <<EOF
KEYSTORE_FILE=keystore/octop-release.jks
KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD
KEY_ALIAS=$ALIAS
KEY_PASSWORD=$KEY_PASSWORD
EOF

chmod 600 "$PROPS_FILE" "$KEYSTORE_FILE"

echo
echo "完成："
echo "  keystore : $KEYSTORE_FILE"
echo "  配置     : $PROPS_FILE （已加入 .gitignore，不会入库）"
echo
echo "下一步可执行 ./scripts/gen-assetlinks.sh 导出 SHA-256 指纹。"
