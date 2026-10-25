# Octop 安卓客户端（开源分享版）

## 概述

为自托管 [Octop](https://github.com/TencentCloud/Octop) 提供一个**可直接下载安装的安卓客户端**，让手机端具备完整的远程控制能力（可指派任务、可见工具真实执行、可上传附件），而不是停留在飞书渠道的只读问答。

本仓库面向**开源分享**：任何部署了 Octop 的人都能用上。同时为想要「无地址栏 + 共享 Chrome 登录态」体验的用户提供 TWA 构建路径。

## 目标

1. 一个 APK 适配**任意** Octop 实例 —— 使用者首次启动填自己的地址即可，无需改代码、无需重新构建。
2. 使用者**不需要**安装 JDK / Android SDK —— 通过 GitHub Release 直接下载。
3. 仓库**不含任何密钥与个人信息**，任何人 clone 后都能构建出自己的版本。
4. 保留 TWA 路径，让追求原生体验的用户也能一键产出无地址栏 APK。

## 背景

- Octop dashboard 本身已是标准 PWA（`manifest.json` 中 `display: "standalone"`、`start_url: "/"`、`scope: "/"`）。
- 手机端目前只能通过飞书链接访问，**且被限制在 `ask` 只读模式**。根因不是飞书渠道限制，而是 agent 配置里的 `conversation_mode = "ask"`；Octop 默认模式是 `craft`。
- 因此「只读」问题的真正解法是**改 agent 模式**，而「像 App」的解法是**封装客户端**。两者互不替代，本方案都覆盖。

## 交付形态

| 交付物 | 说明 | 面向 |
|--------|------|------|
| `app/` WebView 通用壳 | 一个 APK 适配任意实例，首次启动填地址 | 所有人（主交付） |
| `twa/` TWA 配置化构建 | 改一份配置，脚本自动出无地址栏 APK + assetlinks | 想要原生体验的人 |
| `docs/octop-setup.md` | 把自己的 Octop 安全暴露到公网的正确姿势 | 服务端部署者 |

## 仓库结构

```
octop-android/
├── README.md                       # 中文：下载安装 / 快速开始
├── README.en.md                    # 英文简版
├── LICENSE                         # MIT
├── .gitignore                      # 排除 keystore / local.properties / build/ / .gradle/
├── app/                            # 主交付：WebView 通用壳
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/.../               # MainActivity / SetupActivity / SettingsActivity / WebViewClient...
│       └── res/                    # 图标、主题、文案
├── gradle/libs.versions.toml       # 版本目录
├── settings.gradle.kts / build.gradle.kts / gradlew / gradle/wrapper/
├── twa/
│   ├── twa.config.json             # host / 包名 / 应用名 / 版本 / 颜色 / 图标
│   └── scripts/build-twa.sh
├── scripts/
│   ├── gen-keystore.sh             # 生成 keystore（密码落到不入 git 的位置）
│   └── gen-assetlinks.sh           # 从 keystore 导出 SHA-256 → assetlinks.json + Nginx 片段
├── docs/
│   ├── spec.md                     # 本文件
│   └── octop-setup.md
└── .github/workflows/
    ├── webview-apk.yml
    └── twa-apk.yml
```

## 主交付：WebView 通用壳

### 技术栈

Kotlin + 原生 `WebView` + Gradle（Version Catalog）。minSdk 26（Android 8.0），compileSdk / targetSdk 35。

**不引入 Capacitor / Cordova** —— 通用壳不需要混合框架，多一层依赖只增加维护面。

### 首次配置流程

1. 应用启动，读 DataStore 中的实例地址。
2. 未配置 → 进入配置页，输入 `https://你的域名`（自动补 `https://`、去掉尾部 `/`）。
3. 校验：`GET {host}/api/health`，期望 `{"ok":true,"db":true}`。失败给出明确原因（DNS / TLS / 非 Octop 服务 / 网络不可达）。
4. 通过则落库并进入主界面；主界面提供"修改地址"入口，随时可改。

配置页对常见的 URL 输入错误（带路径 `/octop`、带端口、http、漏协议）给出针对性提示。

### WebView 配置清单

以下每一项都对应一个可感知的功能，缺一项就有功能残缺：

| 配置 | 不配的后果 |
|------|-----------|
| `javaScriptEnabled = true` | 页面白屏 |
| `domStorageEnabled = true` | 登录态/会话丢失（SPA 依赖 localStorage） |
| `databaseEnabled = true` | 部分前端存储功能异常 |
| `CookieManager` 持久化 + `setAcceptThirdPartyCookies(true)` | 重启应用需重新登录 |
| `onShowFileChooser` | 附件上传按钮点了没反应 |
| `setSupportMultipleWindows(true)` + `onCreateWindow` | 点击 `target=_blank` 链接无响应 |
| `setMediaPlaybackRequiresUserGesture(false)` | TTS 语音播报无法自动播放 |
| `useWideViewPort` / `loadWithOverviewMode` = true | 移动端视口错乱 |
| `setTextZoom(100)` | 系统大字体破坏页面布局 |
| `DownloadManager` 接管下载（并带上 Cookie 头） | 导出文件不可用 |
| `onRenderProcessGone` 重建 WebView | 渲染进程崩溃直接闪退 |
| 处理 `WindowInsets`（Android 15 强制 edge-to-edge） | 内容被状态栏/导航栏遮挡 |

### 版本兼容

用 `androidx.webkit` 的 `WebViewCompat` / `WebViewFeature` 做特性探测，避免老设备上调用不存在的方法而崩溃。

### 导航与链接

- **返回键**：WebView 可回退则回退，否则退出（`OnBackPressedDispatcher`）。
- **外链**：非实例 host 的链接用 Custom Tabs / 系统浏览器打开，不在壳内加载。
- **上滑刷新**：主界面接 `SwipeRefreshLayout`，流式输出卡住时可手动重载。

### 设置页

- 修改实例地址（改动后清 Cookie 并重载）
- 登出（清 Cookie + localStorage）
- 允许明文 HTTP 开关（见「安全」）
- 关于：版本号、项目主页、协议

### 安全

- 默认强制 HTTPS：`android:usesCleartextTraffic="false"`。
- 为内网自建 / 自签证书用户提供「允许明文 HTTP」开关，**默认关闭**，开启时给出风险提示。
- 自签证书：不放宽 `onReceivedSslError` 的默认行为（即不忽略证书错误），文档里指引用户用受信任证书。
- 不采集任何数据、不做任何上报、不请求网络以外的权限。

### 权限

| 权限 | 用途 | 必要性 |
|------|------|--------|
| `INTERNET` | 访问 Octop | 必须 |
| `RECORD_AUDIO` | Octop 的 browser STT 需要 `getUserMedia` | 可选，运行时可拒 |
| `CAMERA` | 附件拍照上传 | 可选，运行时可拒 |

`WebChromeClient.onPermissionRequest` 中先申请 Android 运行时权限，再回填结果。

### 构建配置

- `applicationId` 默认 `com.octopmobile.app`，在 `app/build.gradle.kts` 中可改。
- 签名：**不提交 keystore**。本地构建走 `scripts/gen-keystore.sh` 生成；CI 走 Secrets 注入。
- 未配置签名时回退到 debug 签名，保证 clone 即可 `./gradlew assembleDebug`。

## 副交付：TWA 配置化

### 配置文件

`twa/twa.config.json`：

```json
{
  "host": "octop.example.com",
  "startUrl": "/",
  "applicationId": "com.example.octop",
  "appName": "Octop",
  "versionCode": 1,
  "versionName": "1.0.0",
  "themeColor": "#ffffff",
  "backgroundColor": "#0f1117",
  "iconUrl": "https://octop.example.com/pwa-512.png",
  "minSdkVersion": 26
}
```

### 构建脚本

`twa/scripts/build-twa.sh` 全自动执行：

1. 校验 `twa.config.json`（host 可达性、`/manifest.json` 与 `/api/health` 可访问）。
2. 由配置生成 `twa-manifest.json`，非交互执行 `bubblewrap init`。
3. 首次运行调用 `scripts/gen-keystore.sh` 生成 keystore；后续复用。
4. `bubblewrap build` 产出签名 APK。
5. 调用 `scripts/gen-assetlinks.sh` 输出两样最容易踩坑的东西：
   - `.well-known/assetlinks.json`（含 keystore 的真实 SHA-256 指纹）
   - 可直接粘贴的 Nginx `location` 片段

### 服务器的 assetlinks 要求

TWA 的 host 在构建时固化，**每个域名各需一个 APK**（这是 TWA 的固有约束，也是本仓库同时提供 WebView 通用壳的原因）。

`assetlinks.json` 暴露方式（写进 `docs/octop-setup.md`）：

```nginx
# 必须放在 HTTPS(443) 块的 location / 之前
location = /.well-known/assetlinks.json {
    default_type application/json;
    return 200 '[{"relation":["delegate_permission/common.handle_all_urls"],
                  "target":{"namespace":"android_app",
                            "package_name":"com.example.octop",
                            "sha256_cert_fingerprints":["<SHA256>"]}}]';
}
```

指纹不匹配时 TWA 会**静默降级**为带地址栏的 Custom Tab，不报错 —— 排查方法见 `docs/octop-setup.md`。

## CI

| 流水线 | 触发 | 产物 |
|--------|------|------|
| `webview-apk.yml` | push / PR / tag `v*` | 构建 `assembleDebug`，上传 APK artifact；打 tag 时创建 Release |
| `twa-apk.yml` | `workflow_dispatch`（输入 host 等参数） | 用 Secrets 注入 keystore 签名，产出 APK + `assetlinks.json` 并附到 Release |

`twa-apk.yml` 需要的 Secrets（缺省时跳过 TWA 流水线，不阻塞）：

- `TWA_KEYSTORE_BASE64`
- `TWA_KEYSTORE_PASSWORD`
- `TWA_KEY_ALIAS`
- `TWA_KEY_PASSWORD`

目的是让使用者**不必安装任何本地工具链**就能拿到 APK。

## 服务器侧（`docs/octop-setup.md`）

网友要跑通客户端，服务端需要满足：

1. Octop 可被公网访问且启用 HTTPS（Let's Encrypt / 自有证书）。
2. Nginx（或等价反代）正确处理 WebSocket 与 SSE：`Upgrade`/`Connection` 透传、`proxy_buffering off`、长 `proxy_read_timeout`、足够的 `client_max_body_size`（附件上传）。
3. **把 agent 的 `conversation_mode` 从 `ask` 改为 `craft`** —— 这才是"只能只读"的根因。说明会同时给出 dashboard 与直接改 SQLite 两种方式。
4. （仅 TWA 用户）配置 `/.well-known/assetlinks.json`。

## 验收清单

**构建与安装**

1. `./gradlew assembleDebug` 在干净环境可成功产出 APK。
2. GitHub Actions 在 push 后产出 APK artifact；打 tag 后 Release 可下载。
3. 仓库中不存在任何 keystore、密码、个人域名或密钥文件（`git log --all --name-only` 抽查确认）。

**通用壳功能**

4. 未配置时进入配置页；填错地址有明确报错；填对后进入主界面。
5. 首次登录成功后，**杀掉应用重开仍保持登录**。
6. 发送一条需要真实执行的指令（例如让 agent 写文件），能看到工具真实调用与流式输出，而非只读拒答。
7. 附件上传可用（从相册选图 / 选文件）。
8. 导出或下载文件可落到系统下载目录。
9. 点击外部链接不在壳内打开。
10. 返回键行为正确；应用切后台再回前台会话不丢。
11. Android 15 设备上内容不被状态栏/导航栏遮挡。
12. 系统字体调到最大时页面布局不崩。

**TWA 路径**

13. `scripts/build-twa.sh` 在干净环境一次跑通。
14. 按脚本输出的 Nginx 片段配置后，安装 APK **无地址栏**（说明 assetlinks 校验通过）。
15. 故意改错指纹时降级为带地址栏（反向验证排查方法有效）。

## 明确不做（YAGNI）

- 不做推送通知、离线缓存、后台任务。
- 不做 iOS 版本。
- 不上架应用商店，不做 Play App Signing。
- 不改 Octop 前端代码，不 fork 上游。
- 不做单应用内多实例并行管理（换地址即切换）。
- 不做账号体系、不做遥测。

## 风险

| 风险 | 影响 | 应对 |
|------|------|------|
| Octop 前端（SPA）在原生 WebView 中存在不兼容的浏览器 API | 主交付不可用 | **首要验证项**：先做最小冒烟 —— 建壳加载现有实例，跑通登录 → 发消息 → 流式输出 → 附件上传，再投入其余功能 |
| 使用者在反代后带子路径部署 | 地址校验与资源加载失败 | 配置页对"带路径"输入给出明确提示；文档说明需部署在域名根路径 |
| TWA 指纹不匹配静默降级 | 用户以为成功实则带地址栏 | 构建脚本输出指纹与排查步骤；`docs/octop-setup.md` 给反向验证方法 |
| Octop 上游更新导致前端行为变化 | 客户端功能回归 | 属外部依赖，README 注明支持范围；冒烟清单可复用 |

## 待定

- 仓库名与 GitHub 组织/用户名（影响 README 徽章与 Release 链接）。
- 应用显示名默认值：暂定「Octop」，可配置。
- 默认图标：暂用占位图标 + 文档说明如何替换为自有实例的 PWA 图标。
