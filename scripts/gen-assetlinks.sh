#!/usr/bin/env bash
# 从 keystore 导出 SHA-256 指纹，生成 Digital Asset Links 文件与可直接粘贴的 Nginx 片段。
#
# 用法：
#   ./scripts/gen-assetlinks.sh <package_name> [host]
# 例：
#   ./scripts/gen-assetlinks.sh com.example.octop octop.example.com
#
# 产出（写到 ./build-assetlinks/）：
#   assetlinks.json      —— 部署到 https://<host>/.well-known/assetlinks.json
#   nginx-location.conf  —— Nginx location 片段
#
# 说明：TWA 会在校验失败时静默降级为带地址栏的 Custom Tab，所以务必核对指纹。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PACKAGE_NAME="${1:-}"
HOST="${2:-octop.example.com}"
ALIAS="${KEY_ALIAS:-octop}"

if [[ -z "$PACKAGE_NAME" ]]; then
  echo "用法: $0 <package_name> [host]" >&2
  exit 1
fi

KEYSTORE_FILE="$ROOT_DIR/keystore/octop-release.jks"
PROPS_FILE="$ROOT_DIR/keystore.properties"

if [[ -f "$PROPS_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$PROPS_FILE"
  KEYSTORE_FILE="$ROOT_DIR/${KEYSTORE_FILE:-keystore/octop-release.jks}"
  KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
fi

if [[ ! -f "$KEYSTORE_FILE" ]]; then
  echo "找不到 keystore：$KEYSTORE_FILE" >&2
  echo "请先执行 ./scripts/gen-keystore.sh" >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "找不到 keytool，请先安装 JDK 17。" >&2
  exit 1
fi

echo "读取指纹（alias=${ALIAS}, keystore=${KEYSTORE_FILE}）..."
FINGERPRINT="$(
  keytool -list -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$ALIAS" \
    -storepass "$KEYSTORE_PASSWORD" 2>/dev/null \
  | grep -i 'SHA256:' | head -1 | sed 's/.*SHA256: //' | tr -d ' '
)"

if [[ -z "$FINGERPRINT" ]]; then
  echo "未能提取 SHA-256 指纹，请检查口令与 alias。" >&2
  exit 1
fi

OUT_DIR="$ROOT_DIR/build-assetlinks"
mkdir -p "$OUT_DIR"

cat > "$OUT_DIR/assetlinks.json" <<EOF
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "$PACKAGE_NAME",
      "sha256_cert_fingerprints": ["$FINGERPRINT"]
    }
  }
]
EOF

cat > "$OUT_DIR/nginx-location.conf" <<EOF
# 粘贴到 HTTPS(443) 的 server 块中，且必须排在 "location /" 之前。
location = /.well-known/assetlinks.json {
    default_type application/json;
    alias $OUT_DIR/assetlinks.json;
}
EOF

echo
echo "SHA-256: $FINGERPRINT"
echo
echo "产出："
echo "  $OUT_DIR/assetlinks.json"
echo "  $OUT_DIR/nginx-location.conf"
echo
echo "部署后可用 https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://$HOST&relation=delegate_permission/common.handle_all_urls 校验。"
