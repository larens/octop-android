# Octop Mobile

给自托管 [Octop](https://github.com/TencentCloud/Octop) 用的安卓客户端。**一个 APK 适配任意实例** —— 装上、填自己的域名，就能在手机上完整地派活、看工具真实执行、传附件，而不是停留在飞书渠道那种只能问不能做的状态。

[English](README.en.md)

## 为什么需要它

手机端访问 Octop 一般走飞书链接，体验上会有两个错觉：

| 现象 | 真正的原因 |
|------|-----------|
| 「只能问答，让它执行任务就被拒」 | agent 配置里 `conversation_mode = "ask"`，Octop 默认是 `craft`。**这是服务端配置问题**，见 [服务端配置](docs/octop-setup.md) |
| 「不像 App，是个网页」 | 缺一个客户端壳 |

两件事互不替代。这个仓库解决第二件，第一件在 [docs/octop-setup.md](docs/octop-setup.md) 里给了改法。

## 两种使用方式

| 方式 | 特点 | 适合谁 |
|------|------|--------|
| **A. 通用壳**（本仓库主交付） | 一个 APK 通吃所有实例，首次启动填地址 | 绝大多数人 |
| **B. TWA**（副交付） | 无地址栏 + 共享 Chrome 登录态，但 host 在构建时固化，**每个域名各需一个 APK** | 想要更原生体验的人 |

### 方式 A：通用壳

1. 到 [Releases](../../releases) 下载最新 `octop-native.apk`。
2. 安装（首次需允许「安装未知来源应用」）。
3. 打开后填入你的实例地址，例如 `https://octop.example.com`，点「连接」。
   - 会自动补 `https://`、去掉尾部 `/`
   - 地址必须部署在**域名根路径**，`https://example.com/octop` 这种带子路径的会被拦下并提示
4. 校验通过后进入主界面，正常登录即可。

应用会记住地址和登录态，下次打开直接进主界面。设置页可以改地址、登出、开启「允许明文 HTTP」（仅内网自建需要，默认关闭）。

### 方式 B：TWA

需要 Node.js 18+ 和 JDK 17：

```bash
git clone https://github.com/larens/octop-android.git
cd octop-android
# 编辑 twa/twa.config.json，把 host 改成你的域名
./twa/scripts/build-twa.sh
```

脚本会依次校验实例可达性、生成工程、生成签名密钥、产出签名 APK，最后输出：

- `twa/build/app-release-signed.apk`
- `build-assetlinks/assetlinks.json`（含真实 SHA-256 指纹）
- `build-assetlinks/nginx-location.conf`（可直接粘贴）

把 `assetlinks.json` 部署到 `https://你的域名/.well-known/assetlinks.json`，具体见 [服务端配置第 4 节](docs/octop-setup.md#4仅-twa-用户配置-assetlinksjson)。

> **注意**：指纹不匹配时 TWA 会**静默降级**成带地址栏的 Custom Tab，不会报错。安装后带地址栏就说明校验没过。

## 服务端要求

至少满足：

1. 公网可访问 + **HTTPS**（客户端默认拒绝明文 HTTP）
2. Nginx 反代正确处理 **WebSocket 与 SSE**（`proxy_buffering off` 必开，否则流式输出会「转圈半天再一次刷完」）
3. agent 的 `conversation_mode` 改为 `craft`（否则只能只读问答）
4. 仅 TWA 用户：配置 `/.well-known/assetlinks.json`

完整配置（含可直接复制的 Nginx 片段和 SQL）见 **[docs/octop-setup.md](docs/octop-setup.md)**。

## 自己构建

前置：JDK 17、Android SDK（`platforms;android-36` + `build-tools;36.0.0`）。

```bash
./gradlew :app:assembleDebug        # 产出 app/build/outputs/apk/debug/
./gradlew :app:test                 # 跑单元测试
```

Release 签名：

```bash
./scripts/gen-keystore.sh           # 生成 keystore + keystore.properties（都不入 git）
./gradlew :app:assembleRelease
```

**仓库不含任何密钥。** 未配置签名时 release 构建会自动回退到 debug 签名，保证 clone 下来就能构建。

CI 用 Secrets 注入签名：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

### GitHub Actions

| 流水线 | 触发 | 产物 |
|--------|------|------|
| [native-apk.yml](.github/workflows/native-apk.yml) | push / PR / tag `v*` | APK artifact；打 tag 时创建 Release |
| [twa-apk.yml](.github/workflows/twa-apk.yml) | 手动触发，填入域名等参数 | TWA APK + `assetlinks.json` |

TWA 流水线需要 `TWA_KEYSTORE_BASE64` / `TWA_KEYSTORE_PASSWORD` / `TWA_KEY_ALIAS` / `TWA_KEY_PASSWORD` 四个 Secrets，未配置则自动跳过。

> **关于安装与升级**：未配置 Secrets 时，CI 用的是 runner 上临时生成的 debug 签名，**每次构建的签名都不一样**，所以新版本装上去会提示「应用未安装」或要求先卸载（数据会丢）。要支持覆盖升级，请生成自己的 keystore 并按上面的名字配好 Secrets。本地构建不受影响——本机 `~/.android/debug.keystore` 是稳定的。

## 客户端做了什么

原生 `WebView`，没有引入 Capacitor / Cordova。以下配置缺一项都会导致可感知的功能残缺：

| 配置 | 缺了会怎样 |
|------|-----------|
| `javaScriptEnabled` | 页面白屏 |
| `domStorageEnabled` | 会话丢失，每次打开都要重新登录 |
| Cookie 持久化 + `setAcceptThirdPartyCookies` | 重启应用需重新登录 |
| `onShowFileChooser` | 附件上传按钮点了没反应 |
| `setSupportMultipleWindows` + `onCreateWindow` | `target=_blank` 链接无响应 |
| `mediaPlaybackRequiresUserGesture = false` | TTS 语音播报无法自动播放 |
| `useWideViewPort` / `loadWithOverviewMode` | 移动端视口错乱 |
| `setTextZoom(100)` | 系统调大字体后布局崩坏 |
| `DownloadManager` 接管下载（带 Cookie） | 导出文件不可用 |
| `onRenderProcessGone` 重建 | 渲染进程崩溃直接闪退 |
| `WindowInsets` 处理 | Android 15 上内容被状态栏/导航栏遮挡 |

其他行为：

- 返回键优先让页面回退，到顶了才退出
- 非本实例的链接走系统浏览器 / Custom Tabs，不在壳内打开
- 主界面支持下拉刷新（流式输出卡住时可手动重载）

## 权限

| 权限 | 用途 | 能否拒绝 |
|------|------|---------|
| `INTERNET` | 访问你的 Octop | 必须 |
| `RECORD_AUDIO` | Octop 的浏览器语音输入（`getUserMedia`） | 可拒绝，拒绝后只是用不了语音 |
| `CAMERA` | 附件拍照上传 | 可拒绝 |
| `WRITE_EXTERNAL_STORAGE` | 保存下载文件（仅 Android 9 及以下） | 可拒绝 |

**不采集任何数据、不做任何上报**，除你填的实例地址外不请求任何网络。

关于明文 HTTP：清单里 `usesCleartextTraffic="true"` 只是为了让「允许明文 HTTP」开关在任何设备上都能生效（静态清单无法运行期切换），实际是否放行由代码层的地址校验把关，**默认只允许 HTTPS**。

## 目录结构

```
app/                            # 主交付：原生客户端（Compose + Room），内含 WebView 兜底
twa/                            # 副交付：TWA 配置化构建
  ├── twa.config.json
  └── scripts/build-twa.sh
scripts/
  ├── gen-keystore.sh           # 生成 keystore
  ├── gen-assetlinks.sh         # 导出 SHA-256 → assetlinks.json + Nginx 片段
  └── write-twa-config.js       # CI 用：由环境变量渲染 TWA 配置
docs/
  ├── spec.md                   # 设计文档
  ├── protocol.md               # 客户端协议契约（M0 冻结）
  └── octop-setup.md            # 服务端配置
.github/workflows/
```

## 支持的版本

面向 Octop 的 PWA 前端（`display: standalone`、`/api/health` 返回 `{"ok":true,"db":true}`）。Octop 上游更新导致前端行为变化时，客户端可能需要跟进——欢迎提 issue。

## 明确不做

推送通知、离线缓存、后台任务、iOS 版本、应用商店上架、单应用内多实例并行管理、账号体系、遥测。

## License

[MIT](LICENSE)
