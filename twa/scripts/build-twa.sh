#!/usr/bin/env bash
# 依据 twa/twa.config.json 生成无地址栏的 TWA APK。
#
# 前置：
#   1. Node.js 18+（用于安装 @bubblewrap/cli）
#   2. JDK 17（bubblewrap 会调用 keytool / jarsigner）
#   3. 目标 Octop 实例已启用 HTTPS，且 /manifest.json、/api/health 可访问
#
# 用法：
#   ./twa/scripts/build-twa.sh
#
# 产出：
#   twa/build/app-release-signed.apk
#   build-assetlinks/assetlinks.json + nginx-location.conf

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TWA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$TWA_DIR/.." && pwd)"

CONFIG="$TWA_DIR/twa.config.json"
BUILD_DIR="$TWA_DIR/build"

if [[ ! -f "$CONFIG" ]]; then
  echo "找不到配置文件：$CONFIG" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "找不到 node，请先安装 Node.js 18+。" >&2
  exit 1
fi

json() { node -e "const c=require('$CONFIG');console.log(c['$1']??'')"; }

HOST="$(json host)"
START_URL="$(json startUrl)"
APP_ID="$(json applicationId)"
APP_NAME="$(json appName)"
LAUNCHER_NAME="$(json launcherName)"
VERSION_CODE="$(json versionCode)"
VERSION_NAME="$(json versionName)"
THEME_COLOR="$(json themeColor)"
BACKGROUND_COLOR="$(json backgroundColor)"
ICON_URL="$(json iconUrl)"
MIN_SDK="$(json minSdkVersion)"

if [[ -z "$HOST" || "$HOST" == "octop.example.com" ]]; then
  echo "请先编辑 ${CONFIG}，把 host 改成的你的 Octop 域名。" >&2
  exit 1
fi

echo "==> 步骤 1/5：校验实例可达性（${HOST}）"
PROBE_HOST="https://$HOST"
HEALTH="$(curl -fsS --max-time 15 "$PROBE_HOST/api/health" || true)"
if [[ "$HEALTH" != *'"ok":true'* ]]; then
  echo "GET $PROBE_HOST/api/health 未返回预期结果，实际响应：$HEALTH" >&2
  echo "请确认实例已启用 HTTPS 且可公网访问。" >&2
  exit 1
fi
curl -fsS --max-time 15 -o /dev/null "$PROBE_HOST/manifest.json" \
  || { echo "GET $PROBE_HOST/manifest.json 不可访问。" >&2; exit 1; }
echo "    实例可达，继续。"

echo "==> 步骤 2/5：准备 bubblewrap"
if ! command -v bubblewrap >/dev/null 2>&1; then
  echo "    未安装 bubblewrap，执行 npm i -g @bubblewrap/cli ..."
  npm install -g @bubblewrap/cli
fi

mkdir -p "$BUILD_DIR"

echo "==> 步骤 3/5：生成 twa-manifest.json 并初始化工程"
cat > "$BUILD_DIR/twa-manifest.json" <<EOF
{
  "packageId": "$APP_ID",
  "host": "$HOST",
  "name": "$APP_NAME",
  "launcherName": "$LAUNCHER_NAME",
  "display": "standalone",
  "themeColor": "$THEME_COLOR",
  "themeColorDark": "#000000",
  "navigationColor": "$BACKGROUND_COLOR",
  "navigationColorDark": "#000000",
  "navigationDividerColor": "#000000",
  "navigationDividerColorDark": "#000000",
  "backgroundColor": "$BACKGROUND_COLOR",
  "enableNotifications": false,
  "startUrl": "$START_URL",
  "iconUrl": "$ICON_URL",
  "maskableIconUrl": "$ICON_URL",
  "splashScreenFadeOutDuration": 300,
  "signingKey": { "path": "./android.keystore", "alias": "octop" },
  "appVersionName": "$VERSION_NAME",
  "appVersionCode": $VERSION_CODE,
  "shortcuts": [],
  "generatorApp": "octop-android",
  "webManifestUrl": "$PROBE_HOST/manifest.json",
  "fallbackType": "customtabs",
  "features": {},
  "alphaDependencies": { "enabled": false },
  "enableSiteSettingsShortcut": true,
  "isChromeOSOnly": false,
  "isMetaQuest": false,
  "fullScopeUrl": "$PROBE_HOST/",
  "minSdkVersion": $MIN_SDK,
  "orientation": "default",
  "fingerprints": [],
  "additionalTrustedOrigins": [],
  "retainedBundles": [],
  "appVersion": "$VERSION_NAME"
}
EOF

echo "==> 步骤 4/5：生成签名密钥（首次）"
if [[ ! -f "$ROOT_DIR/keystore/octop-release.jks" ]]; then
  "$ROOT_DIR/scripts/gen-keystore.sh"
fi
# bubblewrap 期望工程目录下存在 android.keystore，这里做一次软链
ln -sf "$ROOT_DIR/keystore/octop-release.jks" "$BUILD_DIR/android.keystore"

echo "==> 步骤 5/5：构建 APK"
cd "$BUILD_DIR"
bubblewrap build --skipPwaValidation

echo
echo "APK: $BUILD_DIR/app-release-signed.apk"
echo
"$ROOT_DIR/scripts/gen-assetlinks.sh" "$APP_ID" "$HOST"
