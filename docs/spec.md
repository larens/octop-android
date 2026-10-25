# Octop 安卓客户端（Today 化原生版）

> 本文件是 v3，在 v2「原生客户端 + WebView 兜底」的基础上继续演进为**Today 化的个人 Agent 工作台**。v1 的 WebView 代码不删除，继续保留为**兜底通道**。

## 0. 本版相对 v1 的变化

| 项 | v1 | v2（本版） |
|----|----|-----------|
| 主交付 | `app/` WebView 通用壳（加载远端 SPA） | **原生 Compose 客户端**，直连 Octop 的 REST + WebSocket API |
| 「像 App」的手段 | 隐藏地址栏、改 statusbar 颜色 | 原生导航 / 原生列表 / 骨架屏 / 本地会话库 |
| 会话数据 | 全在服务端与浏览器存储 | **镜像进本地 Room**，启动即出内容，不空转 |
| 「一直转圈」 | `loadingView` 全屏遮罩，只在出错时关闭 | 骨架屏 + 顶部细进度条，**不存在全屏 spinner** |
| 通用性 | 一个 APK 适配任意实例（填 URL 即用） | 仍是填 URL 即用，但增加**能力探测**：不兼容的实例自动回落 WebView |
| WebView | 主通道 | 兜底通道（能力不足 / 用户手选 / 原生未覆盖的页面） |
| TWA | 副交付 | 保留不变 |

## 1. 概述

为自托管 [Octop](https://github.com/TencentCloud/Octop) 提供一个**原生安卓客户端**，具备完整的远程控制能力（可指派任务、可见工具真实执行、可传附件），并摆脱浏览器渲染带来的迟滞感。

面向**开源分享**：任何部署了 Octop 的人都能下载安装、填入自己的域名即可使用；仓库不含任何密钥。

## 2. 目标

1. **原生手感**：会话列表、消息渲染、输入、导航全部原生实现；界面无「网页味」，无全屏加载遮罩。
2. **本地缓存**：会话元数据、消息历史、附件缩略图落盘；冷启动先出本地内容，再增量同步。
3. **完整能力**：可发送任务、可取消/转向、可见工具真实调用与流式输出、可传附件、可响应 HITL 确认。
4. **一个 APK 适配任意实例**：首次启动填地址即可；实例 API 面不兼容时自动回落 WebView，不白屏。
5. 使用者**不需要** JDK / Android SDK —— 通过 GitHub Release 下载。
6. 仓库**不含密钥与个人信息**。
7. 在已有「可聊天、可执行、可上传」的基础上，继续做成**围绕人和项目状态运转**的产品：会话可打标签、记忆可整理、任务可定时、Today 页可主动给出下一步。

## 3. 背景：为什么必须转原生（定量结论）

v1 的 WebView 方案实测到的三个根因，都是原生架构能直接消除的：

1. **首包 3.75 MB 未压缩**。Octop 站点 nginx 只对 HTML 开 gzip；`vendor-antd` 2.20 MB、`index` 685 KB、`index.css` 342 KB 全部裸传（带 `Accept-Encoding: gzip, deflate, br` 实测 `content-length` 与裸长度完全一致）。每个冷启动都要重新下载解析。
2. **`/` 是 `cache-control: no-cache`**，且 SPA bundle 内**无 IndexedDB**，会话数据完全不在本地 —— 这是「打开就要等、等完还是空」的成因。
3. **壳自身的问题**：`loadingView` 是全屏遮罩，且只在 `showError()` 里被置 GONE，加载成功后可能一直可见 —— 「一直转圈」的观感有相当一部分来自这里，而不是 WebView 本身。

原生客户端把这三点变成：本地 Room 秒出列表 + 增量同步 + 骨架屏。

同时必须说明：**「只能只读」的根因是服务端 agent 的 `conversation_mode = "ask"`，不是渠道限制**。改模式与换客户端是两件独立的事，本版两者都覆盖。

## 4. 架构决策记录

| 方案 | 说明 | 结论 |
|------|------|------|
| A. 继续 WebView 壳 | 保留 v1 | 已证伪：无法解决上述根因 |
| B. 全原生，删除 WebView | 最纯粹 | 不采纳：一旦实例 API 面不兼容即彻底不可用，违反「任意实例可用」 |
| **C. 原生为主 + WebView 兜底** | 原生跑主链路，WebView 保底 | **采纳** |

**关键设计：能力探测（capability probe）**。客户端启动连接时按顺序探测原生所需的最小 API 面，任一项失败即把这台实例标记为 `webview_only`，直接用 v1 的壳加载，并把该判定持久化到本地（下次不再重复探测）。这样「通用性」和「原生体验」不再互斥。

## 5. 交付形态

| 交付物 | 说明 | 面向 |
|--------|------|------|
| `app/`（原生 + WebView 兜底，同一个 APK） | 原生客户端为主通道，WebView 为兜底 | 所有人（主交付） |
| `twa/` TWA 配置化构建 | 改一份配置，脚本自动出无地址栏 APK + assetlinks | 想要零维护网页壳的人 |
| `docs/octop-setup.md` | 把自己的 Octop 安全暴露到公网的正确姿势 | 服务端部署者 |

## 6. 仓库结构

```
octop-android/
├── app/                                     # 单一 APK：原生为主 + WebView 兜底
│   ├── schemas/                             # Room schema 导出（提交进仓库，供迁移测试）
│   ├── build.gradle.kts
│   └── src/main/java/com/octopmobile/app/
│       ├── OctopApp.kt                      # Application：DI 装配、DB 初始化
│       ├── core/                            # Const / Result / 时间与格式化
│       ├── data/
│       │   ├── db/                          # Room entity / dao / database / migrations
│       │   ├── prefs/                       # DataStore（实例地址、token、主题）
│       │   ├── remote/                      # OctopApi（OkHttp）、dto/、AuthInterceptor
│       │   ├── ws/                          # ChatSocket / 帧编解码 / 重连策略
│       │   └── repo/                        # ThreadRepository / ChatRepository / AttachmentCache
│       ├── ui/
│       │   ├── connect/                     # 首次连接向导（复用 UrlNormalizer + InstanceProbe）
│       │   ├── threads/                     # 会话列表（骨架屏 / 置顶 / 搜索）
│       │   ├── chat/                        # 对话页：流式渲染、工具卡片、HITL
│       │   ├── settings/
│       │   └── web/                         # WebView 兜底 Activity（复用现有 web/ 包）
│       ├── service/                         # 前台服务 + 通知进度
│       └── ui/Handoff.kt                    # 快捷方式 / 分享的一次性交接槽（不再单开 share/ 包）
├── twa/                                     # 副交付（同 v1）
├── scripts/                                 # gen-keystore.sh / gen-assetlinks.sh / write-twa-config.js
├── docs/{spec.md, octop-setup.md, protocol.md}
└── .github/workflows/{native-apk.yml, twa-apk.yml}
```

## 7. 技术栈

| 领域 | 选型 | 说明 |
|------|------|------|
| UI | Jetpack Compose + Material 3 | `compileSdk 36` / `targetSdk 35` / `minSdk 26` |
| 自适应布局 | `androidx.compose.material3.adaptive:adaptive*:1.2.0` | **锁 1.2.0**：1.3.0 硬要求 compileSdk 37 + AGP 9.1.0；`adaptive-navigation3` 仍是 alpha |
| 导航 | `navigation-compose:2.8.5` | 单 Activity 多 destination |
| 本地库 | **Room 2.6.1 + KSP** | `room.schemaLocation = $projectDir/schemas`，schema **提交进仓库** |
| 配置 | DataStore Preferences 1.1.1 | 实例地址、开关 |
| 凭据 | `security-crypto`（EncryptedSharedPreferences） | token 不落明文 |
| 网络 | OkHttp 4.12.0 | REST + 附件上传 |
| 流式 | OkHttp WebSocket 为主；`okhttp-sse:4.12.0` 为备 | 见 §9 |
| 序列化 | kotlinx-serialization-json 1.7.3 | 与 Kotlin 一同编译，无反射 |
| 图片 | Coil 3（compose） | 磁盘缓存即附件缓存 |
| Markdown | mikepenz multiplatform-markdown-renderer-m3 0.33.0 | 流式增量渲染 |
| 浏览器/兜底 | `androidx.browser:1.8.0` + `androidx.webkit:1.12.1` | Custom Tabs + WebView 特性探测 |
| 协程 | kotlinx-coroutines-android 1.9.0 | |

版本组合以 OpenMinis（同类成熟原生客户端）为参照；M0 阶段在 CI 上跑通一次以确认可解析。

**不引入**：Capacitor / Cordova / Flutter / React Native。原生链路已经足够短，跨平台框架只会加一层需要调试的黑盒。

## 8. 数据层（Room）

### 8.1 设计原则

- **服务端是真值来源，本地库是显示缓存**：本地永远可以整体重放/重建，不做「本地改动待合并」的复杂同步。
- **流式消息用同一行持续 UPDATE**，不追加新行 —— 这样杀进程重进也能看到半截输出。
- 消息正文用 `parts_json`（parts 数组）存储，与 WS 事件天然同构：`text` / `reasoning` / `tool_call` / `tool_result` / `attachment` / `error`。

### 8.2 表

| 表 | 关键字段 |
|----|---------|
| `instances` | `base_url`(PK) / `display_name` / `capability`(native\|webview_only) / `last_connected_at` |
| `threads` | `thread_id`(PK) / `agent_id` / `title` / `category` / `last_message` / `pinned_at` / `updated_at` / `unread_count` / `model_id` |
| `thread_tags` | `thread_id` / `tag` / `kind(project\|topic)` / `created_at` —— 一个会话可挂多个项目/主题标签，作为记忆归档与任务作用域的主锚点 |
| `memory_items` | `id` / `kind(person\|project\|preference\|commitment)` / `title` / `summary` / `source_thread_id` / `source_message_id` / `updated_at` —— 结构化、可审计的长期记忆 |
| `automation_tasks` | `id` / `title` / `prompt` / `schedule_rule` / `scope_kind(all\|tag\|thread)` / `scope_ref` / `status` / `last_run_at` / `next_run_at` / `created_at` —— 可重复执行的自动任务 |
| `messages` | `id`(PK) / `thread_id` / `turn_id` / `role` / `sort_order` / `parts_json` / `status` / `error_info` / `stream_interrupt_count` / `updated_at` |
| `compact_markers` | `thread_id` / `created_at` / `summary` —— 标记上下文压缩点，UI 画分隔线。**来源是轨迹接口**（`GET …/trajectory?kinds=compacted`，protocol.md §5.4），`created_at` = 事件的 `ts × 1000`，与 `messages.sort_order` 同量纲 |
| `attachments` | `id` / `thread_id` / `message_id` / `remote_url` / `local_path` / `mime` / `size` / `state` |
| `pending_ops` | `id` / `kind`(send\|cancel\|steer) / `thread_id` / `payload_json` / `retry_count` / `created_at` —— 断网时排队，恢复后重放 |

### 8.3 与 Octop 概念的字段映射

| Octop | 本地 |
|-------|------|
| `thread_id` | `threads.thread_id` / `messages.thread_id` |
| `turn_id` / `expected_turn_id` | `messages.turn_id`；客户端用 `client_pending_id` 关联本地占位消息 |
| `usage` 事件 | **不存在**：上游只在服务端内部统计用量，WS 不发该事件（protocol.md §4.2） |
| `reasoning` 事件 | `parts_json` 内的 `reasoning` part |
| `compacted` 轨迹事件 / 上下文压缩 | `compact_markers`（压缩**不推 WS**，只能走轨迹接口拉） |

排序统一按 `pinned_at` 优先、其后 `updated_at DESC`。

### 8.4 迁移

schema 导出目录提交进仓库；每次改表必须附迁移 + 一条 JVM 可跑的迁移测试（`MigrationTestHelper`）。**不允许 `fallbackToDestructiveMigration`** —— 那等于用户的本地会话会被静默清空。

## 9. 网络层与协议

### 9.1 鉴权

- `Authorization: Bearer <token>`；登录 `POST /api/auth/login`，自省 `GET /api/auth/me`。
- WS 通过查询参数 `?token=` 传凭据。
- 401 响应体形如 `{"error":{"code":"AUTH_FAILED","message":"...","details":{}}}` → 统一映射为「登录已失效」，引导重新登录而不是弹一堆解析错误。

### 9.2 REST 面（客户端用到的）

| 用途 | 方法 / 路径 |
|------|------------|
| 健康检查 | `GET /api/health` → `{"ok":true,"started_at":…,"db":true,"users_loaded":…,"agents_running":…}` |
| 登录 / 自省 | `POST /api/auth/login`、`GET /api/auth/me` |
| 线程列表 / 新建 | `GET /api/agents/{id}/threads?limit=`、`POST /api/agents/{id}/threads` |
| 线程详情 / 改名 | `GET /api/agents/{id}/threads/{tid}?compact=`、`PATCH …/{tid}` |
| 历史 | `GET …/{tid}/history?limit=` |
| 上下文用量 | `GET …/{tid}/context-usage` |
| 分叉 / 已读 | `POST …/{tid}/fork`、`POST …/{tid}/read` |
| 附件上传 | `POST /api/agents/{id}/upload`（multipart，字段名 `file`）→ `{path, workspace_path, url, filename, media_type}` |
| HITL 确认 | `POST /api/agents/{id}/chat/hitl/resume`（**SSE 响应**，body `{thread_id, decisions[], pending_id?}`） |

### 9.3 实时通道

- 对话：`WS /api/agents/{agentId}/chat/ws?token=…`
- 通知：`WS /api/notifications/ws`
- 备选：SSE 通道（`okhttp-sse`），当实例的 WS 被反代阻断时启用

**客户端 → 服务端帧**

| `type` | 字段 | 用途 |
|--------|------|------|
| `user_turn` | `text`, `thread_id`, `client_pending_id`, `messages?`(带附件时的 content blocks), `delivery:"auto"`, `expected_turn_id?` | 发送任务 |
| `cancel` | `thread_id` | 中断当前轮 |
| `subscribe` | `thread_id` | 订阅某线程的增量事件 |
| `steer_remove` | `thread_id`, `turn_id`, `client_pending_id`, `pending_id` | 撤回排队中的转向指令 |
| `ping` | — | 心跳保活 |

**服务端 → 客户端事件**

`turn.started` / `turn.resumed` / `turn.ended`（回合生命周期）、`token`（正文增量）、`reasoning`（思考增量）、`tool_call_chunk`（工具调用参数增量）、`tool_result`、`attachment`、`done`、`error`、`hitl_required`（需要人工确认）、`turn_status`（`subscribe` 应答）、`pong`、`user`（**仅 steer 合并时**回显）、`steer.*`。

> 逆向阶段的两个错误已在 M0 核对中更正：**不存在** `usage` 事件，也**不存在** `state_snapshot` —— 「这个线程是否在跑」只能靠 `subscribe` 后返回的 `turn_status.active`（protocol.md §4.2）。

### 9.4 契约来源与可信度（重要）

上表的帧名与事件名来自**对线上 SPA bundle 的逆向**（`https://<你的实例>/assets/index.*.js`），不是从服务端源码读出的。可信度分级：

| 级别 | 内容 | 处理 |
|------|------|------|
| 已实测 | `/api/health` 响应体、`/` 与 `/assets/*` 的缓存头 | 可直接依赖 |
| 逆向得出 | WS 路径、5 种客户端帧、17 种服务端事件、threads 路由族 | **M0 必须与上游源码逐条核对** |
| 待确认 | 附件的服务端 URL 形态与鉴权方式；实例版本号（`/api/health` 未返回版本字段） | M0 补齐 |

核对对象（上游 `TencentCloud/Octop`，MIT）：`src/octop/api/routers/chat/{ws.py, serialize.py, sse.py, turn.py, history.py}`、`src/octop/api/routers/mobile/{stream.py, shell_ws.py, status.py, install.py}`，权威参考测试 `tests/integration/test_chat_ws.py`、`tests/unit/gateway/test_dashboard_ws.py`。

核对结论写进 `docs/protocol.md`（含要固化的 DTO 与「不支持则降级」的判定点）。**M0 不通过就不进入 M1** —— 整个原生方案都建立在这份契约上。

### 9.5 能力探测与降级

| 探测项 | 方法 | 失败时的降级 |
|--------|------|------------|
| 服务存在 | `GET /api/health` 返回 `ok:true` 且 `db:true` | 连接向导报明确原因（DNS / TLS / 非 Octop 服务） |
| 可登录 | `POST /api/auth/login` + `GET /api/auth/me` | 停在登录页 |
| 有 agent | `GET /api/agents/…` 可取到列表 | 标记 `webview_only` |
| 对话 WS 可用 | 连 `/api/agents/{id}/chat/ws` 并发送 `subscribe`，期待 `turn_status` | 试 SSE；再失败 → 标记 `webview_only` |
| 附件上传可用 | 预检上传接口 | 该功能隐藏并提示 |

判定为 `webview_only` 时：Toast 说明原因 → 用 v1 的 WebView Activity 加载该实例 → 持久化该判定。

## 10. 断线重连与「断流不丢消息」

- **心跳**：空闲 30s 发 `ping`，15s 未见任何入帧则判定链路死。
- **重连**：指数退避（1s→2s→4s→…→上限 30s），带抖动；应用回前台立即重连一次。
- **重连后不全量拉历史**：重连成功先重新 `subscribe`，用应答里的 `turn_status.active` 校正本地「是否在跑」，再收增量。全量历史只在**首次打开线程**时用 `/history?limit=` 拉一页，向上翻页续拉。
- **半截消息**：流式中断时，本地那行 `status = interrupted`，`stream_interrupt_count += 1`，UI 明确显示「输出中断」并提供「重新拉取该轮」。
- **发送失败**：入 `pending_ops`，恢复网络后按序重放；重放前先与 `expected_turn_id` 对齐，避免重复发一轮。

## 11. 页面与信息架构

- **首次连接向导**：输入地址 → 规范化（自动补 `https://`、去尾部 `/`）→ 探测（§9.5）→ 登录 → 选 agent → 进会话列表。对「带路径 / 带端口 / http / 漏协议」给出针对性提示。
- **会话列表**：骨架屏占位 → 置顶分组 → 未读标记 → 下拉刷新（触发增量同步，不是重载页面）→ 长按置顶/删除/改名。
- **对话页**：顶部标题 + 连接状态点；消息流；底部输入框改为 **Today 风格操作流**：左侧 `+` 展开「上传文件 / 拍照 / 上传图片 / 录制语音笔记」，右侧为语音输入，文本发送走键盘 IME；回合中右侧按钮切为「中止」。工具调用以卡片呈现（工具名 + 参数折叠 + 结果摘要）；`hitl_required` 弹原生确认卡片 —— **这是「可派活」闭环的关键一环**；`reasoning` 默认折叠。
- **Today 页**：不显示空白输入框，改为围绕「今天值得推进什么」的主动卡片流。卡片至少分为三类：`今天值得看`（外部变化）、`现在可以推进`（已准备好下一步）、`等你确认`（涉及写操作/发送/改数据）。
- **任务页**：集中展示一次性任务与自动任务。自动任务支持按时间规则重复执行，并限定作用域为「全部 / 某标签 / 某会话」。
- **记忆页**：集中管理结构化记忆与会话标签。标签分成 `项目` 与 `主题` 两类，记忆条目允许查看、修改、删除，来源可追溯到线程或消息。
- **设置页**：改地址（切换实例）、登出、允许明文 HTTP、主题跟随系统、关于（版本 / 主页 / 协议）、清理本地缓存。
- **WebView 兜底页**：复用 v1 的 `OctopWebViewClient` / `OctopWebChromeClient`（新窗口、文件选择、网页权限三项处理继续沿用），用于 `webview_only` 实例、用户手选「网页模式」、以及原生未覆盖的管理类页面。

## 12. 手感专项（本版的核心卖点）

| 议题 | 做法 |
|------|------|
| 加载态 | **彻底移除全屏 `ProgressBar`**。列表用骨架屏（本地为空且还没问到服务端时），会话用气泡占位；后台同步用顶部细进度条（2dp，与会话页同规格），下拉刷新用下拉指示器自带转圈 |
| 冷启动 | 先进 UI，立刻用 Room 数据渲染；网络同步在后台跑 |
| 流式渲染 | token 到达后按 ~50ms 合帧；**只重新解析「最后一个仍在流式的块」**，历史块不重解析 |
| 滚动跟随 | 仅当用户位于底部 32dp 死区内才自动跟随；用户一上滑即解除锁定并浮出「回到底部」按钮 |
| 长内容保护 | 单条正文超 20k 字符默认折叠（可展开，流式中的那条不折）；含超长单行（>200 字符）的正文不折行、改横向滚动 —— 纯文本渲染下这是「代码块/表格横向滚动」的近似判据 |
| 回弹 | 用 Compose 原生 overscroll（Android 12+ stretch），不再用 `SwipeRefreshLayout` |
| 图片 | Coil 磁盘缓存 + 固定尺寸占位，避免流式重排导致列表跳动 |
| 大字体 | 跟随系统字号，不做 `setTextZoom(100)` 式硬锁；气泡宽度上限随字号放宽；无 `Scaffold` 的页面（连接 / 登录）用 `safeDrawingPadding` + 可滚动列，最大字号下不被系统栏或键盘裁切 |

## 13. 内容缓存与资源寻址

- **缓存对象**：会话元数据、消息历史、附件（原图与缩略图）、agent 列表、上一版 app 配置。
- **存储位置**：Room（结构化）+ `filesDir/attachments/<thread_id>/`（二进制）。附件缓存受总容量上限约束（默认 200 MB），LRU 淘汰；设置页可一键清理。
- **引用形式**：服务端返回的附件 URL 一律由客户端**归一化为稳定引用**并落库，UI 内联渲染（图片直出、文件出可点卡片）。同一引用命中本地缓存则不发网络请求。
  - 归一化的具体形态（自定义 scheme vs 原 URL + 本地索引）取决于 M0 对「服务端附件 URL 形态」的确认结果，见 §9.4「待确认」。
- **不做**：不做离线对话（无网络的轮次仍排队）、不做全文离线检索、不做附件双向同步。

## 14. 系统集成

| 能力 | 说明 |
|------|------|
| 前台服务 | 长任务执行中保持连接并显示进度通知；`POST_NOTIFICATIONS`（33+）运行时申请，被拒则静默降级 |
| 通知进度 | SDK 36+ 走 Live Updates（`Notification.ProgressStyle`），以下版本用普通进行中通知。注：android-36 公开 API 里没有 `setRequestPromotedOngoing`，实测只做「前台服务 + 进行中通知」，不追求状态栏胶囊提升 |
| App Shortcuts | 新会话 / 语音输入 / 拍照 |
| 分享入口 | `ACTION_SEND` / `SEND_MULTIPLE`（文本、图片、文件）→ 落到一个新会话里（不做追加） |
| 深链 | `octopmobile://thread/{id}`，用于通知点击与会话直达（自定义 scheme，无需每个实例配 assetlinks） |

## 15. 安全与权限

- 默认 `android:usesCleartextTraffic="false"`；为内网自建提供「允许明文 HTTP」开关，默认关闭并给风险提示。
- 不放宽 `onReceivedSslError`（不忽略证书错误），文档指引使用受信任证书。
- 登录 token 存 EncryptedSharedPreferences；登出时清 token + 本地缓存 + Room 数据（可选保留）。
- 不采集任何数据、不做上报。

| 权限 | 用途 | 必要性 |
|------|------|--------|
| `INTERNET` | 访问 Octop | 必须 |
| `POST_NOTIFICATIONS` | 任务进度通知（33+） | 可选，可拒 |
| `FOREGROUND_SERVICE` + `…_DATA_SYNC` | 长任务保持连接 | 可选 |
| `RECORD_AUDIO` | 语音输入 | 可选，可拒 |
| `CAMERA` | 拍照发附件 | 可选，可拒 |

## 16. 构建与 CI

- `applicationId`：原生版改用 `com.octopmobile.native`，**与 v1 的 `com.octopmobile.app` 区分**（两者签名不同，混装必须先卸载；分开包名可避免用户误装）。
- 签名：**不提交 keystore**。本地走 `scripts/gen-keystore.sh`，CI 走 Secrets。未配置时回退 debug 签名，保证 clone 后即可 `./gradlew assembleDebug`。
- 变体：`debug` / `release`；另加 `perf`（`initWith(debug)` + `isDebuggable = false` + `isProfileable = true`）用于测量真实帧耗时 —— debuggable 会让 ART 拒绝 AOT，测量结果不可代表用户环境。

| 流水线 | 触发 | 产物 |
|--------|------|------|
| `native-apk.yml` | push / PR / tag `v*` | `assembleDebug` 产物作为 artifact；打 tag 创建 Release |
| `twa-apk.yml` | `workflow_dispatch` | 同 v1（Secrets 注入签名） |

## 17. 服务端前置（`docs/octop-setup.md`）

1. Octop 可被公网访问且启用 HTTPS。
2. Nginx 正确处理 WebSocket 与 SSE：`Upgrade` / `Connection` 透传、`proxy_buffering off`、长 `proxy_read_timeout`、`client_max_body_size` 足够大（附件上传，建议 512m）。
3. **把 agent 的 `conversation_mode` 从 `ask` 改为 `craft`** —— 「只能只读」的根因。
4. **开启 JS/CSS 压缩**（`gzip_types` 或 brotli）：原生客户端主链路不依赖它，但 WebView 兜底通道与首次加载仍直接受益。
5. （仅 TWA 用户）配置 `/.well-known/assetlinks.json`。

## 18. 里程碑

| 阶段 | 内容 | 出口条件 |
|------|------|---------|
| **M0 契约冻结** | 对照上游源码与真实实例核对 §9 全部帧与路由；产出 `docs/protocol.md`；搭起 Compose + Room + KSP 骨架在 CI 跑通 | 契约逐条确认，DTO 定型 |
| **M1 只读原生** | 连接向导 + 能力探测 + 登录 + agent 选择 + 会话列表（Room）+ 历史分页 + 流式接收（`token`/`reasoning`/`done`） | 装到真机，冷启动秒出列表，能流畅看一段真实流式输出 |
| **M2 可写** | 新建会话（`POST /threads`）+ `user_turn` 发送 / `cancel` 中止 + 附件上传 + 工具折叠卡片 + `hitl_required` 原生确认卡（SSE `hitl/resume`）+ `pending_ops` 重放 | 手机下发任务，服务端真实执行并回传 |
| **M3 手感与缓存** | 骨架屏、合帧渲染、滚动锁定、附件缓存与清理、紧凑标记、断线重连与半截消息 | 无全屏 spinner；飞行模式切换后不丢消息 |
| **M4 集成与兜底** | 前台服务 + 通知、Shortcuts、分享入口、深链、WebView 兜底通道接通、TWA 流水线回归 | 验收清单全绿 |

### 18.1 M2 实现决策（本轮确认）

| 决策点 | 结论 | 理由 |
|--------|------|------|
| 新建会话时机 | 点「+」**立即** `POST /api/agents/{id}/threads`，随后进入该会话 | 与网页版 `/new` 一致。代价要说清：列表会立刻多一条空会话，且服务端把该 agent 的 **dashboard 会话重绑到新 thread**，同一账号的网页端会话会被切走（旧会话不删，仍在列表里） |
| 回合进行中再发送 | 输入框**置灰**、发送按钮变「中止」；等 `turn.ended` 后才允许再发 | 本轮**不做 steer**（`delivery:"steer"` + `expected_turn_id`）：它需要 `steer.queued/rejected/drained` 全族状态机与本地待发队列 UI，收益低于复杂度 |
| 用户消息的本地回显 | 客户端**自己**先落一条本地行 | 上游只在 steer 合并时下发 `user` 帧（`ws_channel.py:413-425` `_steer_user_frame`），正常回合**不回显**用户消息 |
| 附件上传的发送形态 | 走 `user_turn.messages` 的 content blocks（`workspace_path` + `filename` + `media_type`），不走 `text` | 上传接口返回的就是这三个字段（protocol.md §6） |
| HITL 续跑 | 确认后由客户端消费 `POST …/chat/hitl/resume` 的 **SSE 流**，把帧喂进同一条事件管线 | 该接口的续跑增量只在 SSE 上，**不推 WS hub**；不消费 SSE 就看不到后续输出 |
| 发送失败 | 入 `pending_ops`，socket 重新 `Open` 后按 `createdAt` 顺序重放 | 验收第 14 条：断网发送不丢、不重复发 |

## 19. 验收清单

**构建与开源卫生**

1. 干净环境 `./gradlew assembleDebug` 成功。
2. push 后 CI 产出 APK artifact；打 tag 后 Release 可下载。
3. `git log --all --name-only` 抽查确认仓库无 keystore / 密码 / 个人域名。

**契约与连接**

4. `docs/protocol.md` 中每条帧/路由都能指出上游源码依据或实测依据。
5. 未配置时进连接向导；填错地址给出具体原因（而非「加载失败」）。
6. 正常实例探测为 `native`；构造一个 API 不兼容的实例时自动回落 WebView 且**不白屏**。
7. 首次登录后杀掉应用重开仍是登录态。

**核心链路**

8. 会话列表冷启动**先出本地内容**，无全屏加载遮罩。
9. 打开线程能看到历史消息，上滑可分页。
10. 发送「需要真实执行的指令」（例如让 agent 写文件），能看到工具真实调用与流式输出，而非只读拒答。
11. 流式过程中点「中止」能停下，且消息标记为中断、可重新拉取。
12. 附件上传可用（相册选图 / 选文件），上传后能在会话里内联看到。
13. HITL 确认弹窗可用，确认后任务继续。
14. 断网后发送进入待发队列，恢复网络后自动送达且不重复发送。

**手感与缓存**

15. 全程不存在全屏转圈；后台同步只由顶部细进度条提示，下拉刷新的转圈只在下拉期间出现。
16. 用户上滑浏览历史时不被打断；回到底部按钮生效。
17. 长消息（20k+ 字符）滚动不掉帧，可折叠。
18. 附件二次打开命中本地缓存。
19. 清缓存后应用仍可用，重新拉取正常。

**系统集成**

20. 长任务切后台不被系统杀死，通知显示进度。
21. 从其他 App 分享文本/图片到客户端能落地成会话内容。
22. Android 15 上内容不被状态栏/导航栏遮挡；系统最大字号下布局不崩。

**TWA（沿用 v1）**

23. `scripts/build-twa.sh` 干净环境一次跑通；配好 Nginx 后安装无地址栏；故意改错指纹时降级为带地址栏。

## 20. 明确不做（YAGNI）

- 不做端侧 agent 沙箱（不跑 PRoot / 本地 Linux，不做 OpenMinis 的竞品形态）—— Octop 的能力在服务端。
- 不做 iOS。
- 不上架应用商店，不做 Play App Signing。
- 不做账号体系、不做遥测。
- **不改 Octop 前端代码，不 fork 上游**；只做客户端 + 文档（若 §17 第 4 条值得上游合入，可单独提 PR）。
- 不做多实例并行管理（切换地址即切换，本地保留各实例的缓存目录）。
- **不做离线对话 / 离线全文检索**（v1 曾整条禁止离线缓存，本版修正为：**做**元数据、历史与附件的本地缓存，**不做**离线推理）。
- 不做附件双向同步（只单向缓存服务端产物；需要上传的走正常附件上传）。

## 21. 风险

| 风险 | 影响 | 应对 |
|------|------|------|
| **API 契约来自逆向，与上游实现存在偏差** | 主交付不可用 | 视作首要风险：M0 只做核对与骨架，不核对完不进入 M1 |
| 上游无版本号，内部结构可能重构 | 升级后客户端回归 | 能力探测 + 失败回落 WebView + `docs/protocol.md` 作为回归基准 |
| 实例的 WS 被反代阻断（缺 `Upgrade` 透传） | 实时通道不可用 | 探测阶段识别 → 试 SSE → 再失败回落 WebView；`octop-setup.md` 给 Nginx 片段 |
| 流式渲染性能（大 Markdown 高频重排） | 掉帧，手感反而变差 | 合帧 + 仅重解析流式块 + 长内容折叠；`perf` 变体量化 |
| Room 迁移事故 | 用户本地会话被清空 | schema 提交 + 迁移测试；禁用破坏性迁移 |
| 「原生为主」被理解为放弃通用性 | 使用者对兼容性预期错位 | README 明确写出能力探测与自动兜底的机制与边界 |

## 22. 待定

- 是否**继续发布 v1 的 WebView 壳 APK**（建议保留，作为「纯兜底版」对外）。
- 是否把 nginx 压缩配置（§17 第 4 条）提 PR 给上游。
- 应用显示名默认值（暂定「Octop」）、仓库名与 GitHub 归属。
- 主题是否提供自定义配色（暂定仅跟随系统深/浅色）。
- `docs/protocol.md` 是否对外公开（若上游视为内部细节，则只保留「已确认的 DTO + 降级判定点」）。

## 23. V3 Today 化升级（本轮新增）

### 23.1 产品判断

本轮调研后的结论是：**Today 的核心不是多专家聊天，而是“围绕用户状态运转的长期记忆 + 主动推进 + 可持续执行”**。因此 Octop Android 的优先级调整为：

1. **先改输入层**：让对话入口具备 Today 式多模态操作流。
2. **再补记忆锚点**：标签、结构化记忆、可追溯来源。
3. **再做 Today 页**：把会话、任务、记忆重组为「今天要推进什么」。
4. **再做自动任务**：定时/规则执行重复工作。
5. **最后做多专家协作**：以 `@专家` 方式增强，而不是一开始就做群聊式多 agent UI。

### 23.2 输入层重构

输入区采用以下交互约束：

- 左侧只有一个 `+` 主按钮，不再平铺多个入口；
- `+` 展开四个动作：`上传文件`、`拍照`、`上传图片`、`录制语音笔记`；
- 右侧不再放发送按钮；**发送走键盘的 Send/Done**；
- 右侧按钮用于**语音输入（说话转文字）**，不是音频附件；
- `录制语音笔记` 与 `语音输入` 必须区分：前者产出音频附件，后者产出可编辑文本；
- 回合进行中 / HITL 挂起时，右侧按钮切换为 `中止`。

### 23.3 标签、记忆、任务的最小模型

- **标签**：会话支持多标签；标签必须显式区分 `项目` 与 `主题` 两类。
- **记忆**：只沉淀结构化、可审计的记忆，不把整段聊天原样抄进「记忆」页。首批只支持 `人物`、`项目`、`偏好`、`承诺` 四类。
- **任务**：自动任务以 `schedule_rule + scope` 为核心。`scope` 支持 `全部`、`标签`、`会话` 三档；任何会改外部状态的动作默认仍需用户确认。

### 23.4 Today 页的卡片模型

Today 页不做成普通列表，而是把现有数据重组为三种卡片：

- **今天值得看**：新的外部变化、等待处理的消息、该知道的提醒；
- **现在可以推进**：已经具备下一步条件的事项；
- **等你确认**：涉及发消息、改内容、执行动作的待确认项。

每张卡片至少包含：

- 为什么现在出现；
- 来自哪个会话 / 标签 / 记忆 / 任务；
- 建议的下一步；
- 一键进入相关上下文。

### 23.5 多专家的边界

多专家不是本轮主轴。最小可行方案定义为：

- 用 `@planner` / `@research` / `@reviewer` 这类角色切换增强单线程；
- 专家首先是不同的提示词、工具权限与输出模板，不急着做多线程编排；
- 所有结果仍回到同一会话，避免过早引入复杂的多 agent 状态同步。

### 23.6 工作台导航骨架

会话、`Today`、任务、记忆是**同一层**的四块，用**底部导航**切换（`WorkbenchScreen`），不再互相往对方页面的顶部放跳转按钮 —— 那种「页头放兄弟页面入口」的样式在只有两三个入口时还凑合，到四块之后就会把页头变成长满按钮的工具栏。

具体约束：

- 全应用只有 `WorkbenchScreen` 这一层持有 `Scaffold` 与 `NavigationBar`；四个栏自己**不带顶部栏**。
- 唯一例外是会话列表：它的页头兼着「新建会话 / 设置」两个**不是页面跳转**的动作，所以保留 `TopAppBar`。
- 任务页 / 记忆页用 `PaneHeader` 作页内标题（内容的第一行，不是 `TopAppBar`）。
- 栏间切换**不进返回栈**：导航图里只注册一个 `threads` destination，返回键从任何一栏都退回上一级。
- 选中栏用 `rememberSaveable` 记住，从会话详情返回时回到离开前那一栏。
- 外层 `Scaffold` 算出的 `padding` 通过 `.padding(padding).consumeWindowInsets(padding)` 传给内容，避免状态栏高度被算两遍。

### 23.7 分阶段落地顺序

| 阶段 | 目标 | 状态 |
|------|------|---------|
| **M5-1 输入层** | `+` 菜单、图片/文件/相机/语音笔记、右侧语音输入、键盘发送 | 已落地 |
| **M5-2 记忆锚点** | 标签、结构化记忆、来源追溯 | 已落地（标签纯本地、记忆纯本地，理由见 `data/repo/TagRepository.kt` / `MemoryRepository.kt`） |
| **M6 Today 页** | 主动卡片流、基于现有会话/任务/记忆的聚合展示 | 已落地第一版（等你确认 / 正在执行 / 最近推进 + 记忆摘要） |
| **M7 自动任务** | 定时/规则任务、按标签/会话作用域执行 | 已落地镜像与增删改（`docs/protocol.md` §5.5）；`scope` 仍是服务端的 `session_key`，未做「按标签」选择器 |
| **M8 多专家增强** | `@专家` 角色切换与团队协作 | 待实现 |
