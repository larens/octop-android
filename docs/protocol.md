# Octop 客户端协议契约（M0 产出）

本文档是原生客户端的唯一协议依据。**每条都标注了上游源码位置**；标「未找到」的是上游不存在或未使用的东西，不要凭想象实现。

上游版本：`TencentCloud/Octop`，浅克隆核对。核对日期 2026-10-25。

---

## 1. 路由前缀

`src/octop/api/app.py`（`_RouterMount` 注册表）：

| 模块 | 前缀 |
|------|------|
| `health` | `/api/health` |
| `auth` | `/api/auth` |
| `agents` | `/api/agents` |
| `chat` | `/api/agents` |
| `uploads` | `/api/agents` |
| `workspace` | `/api/agents` |
| `mobile` | `/api` |

---

## 2. 鉴权

| 项 | 值 | 依据 |
|----|----|------|
| 登录 | `POST /api/auth/login` | `api/routers/auth.py:170` |
| 请求体 | `{"username": str, "password": str, "captcha_token": str?}` | `auth.py:63-66` |
| 响应体 | `{"access_token": str, "token_type": "Bearer", "expires_in": int, "user": {...}}` | `auth.py:202-207` |
| user 字段 | `id`, `username`, `role`, `display_name`, `locale`, `permissions` | `auth.py:25-34` |
| 自省 | `GET /api/auth/me` → 同上 user 结构 + `avatar_url` 等 | `auth.py:217-222` |
| 携带方式 | `Authorization: Bearer <token>`，或 query `access_token` | `api/deps.py` `extract_raw_token()` |
| 自动续期 | 剩余寿命 < 1/3 TTL 时响应头返回 `X-Octop-Access-Token` | `api/deps.py` `maybe_sliding_renew_token()` |
| 登出 | `POST /api/auth/logout`（204，仅审计；JWT 无状态不吊销） | `auth.py:210` |
| 未知账号/密码错 | `AUTH_FAILED`（`infra/errors.py`） | `auth.py:198` |
| 登录锁定 | `LOGIN_LOCKED`，`details.retry_after_seconds` | `auth.py:163-167` |
| 未初始化 | `SETUP_REQUIRED`（用户表为空时） | `auth.py:182-183` |

**客户端要点**：必须处理 `X-Octop-Access-Token`，否则 token 过期后要重登。

---

## 3. 健康检查

`GET /api/health`，**无需鉴权**（`api/routers/health.py:14-28`）：

```json
{"ok": true, "started_at": 1792900437, "db": true, "users_loaded": 2, "agents_running": 2}
```

**没有版本号字段** —— 因此能力探测不能依赖版本号，必须靠实际调用探测（见 §7）。

---

## 4. 聊天 WebSocket

`WS /api/agents/{agent_id}/chat/ws?token=<jwt>`（`api/routers/chat/ws.py:37-42`）

- 鉴权：**只有 query 参数 `token`**。缺失 → `close(4001, "missing token")`；无效 → `close(4001, "auth: <CODE>")`；无权限 → `4003`；agent 不存在 → `4404`。（`ws.py:45-75`）
- 断开连接**不会**取消进行中的回合；重连后 `subscribe` 可续收。（`ws.py:258-261` 注释）

### 4.1 客户端 → 服务端

| `type` | 字段 | 依据 |
|--------|------|------|
| `ping` | — | `ws.py:101-103` |
| `subscribe` | `thread_id`（必填，空则回 `error`） | `ws.py:105-148` |
| `cancel` | `thread_id`（必填） | `ws.py:150-177` |
| `steer_remove` | `thread_id`, `turn_id`（必填）, `client_pending_id`, `pending_id` | `ws.py:698-762` |
| `user_turn` | 见下 | `ws.py:190-249` |

未知类型 → `{"type":"error","message":"unknown message type: X"}`（`ws.py:190-192`）。
非 JSON 文本会被当作 `{"type":"user_turn","text":<原文>}`（`ws.py:94-95`）。

**`user_turn` 帧**（`chat/models.py:143-166` `UserTurnWsFrame`，等价 `ChatTurnBody`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `text` | str? | WS 简写：纯文本消息 |
| `messages` | list? | OpenAI 风格消息数组；与 `text` 二选一 |
| `thread_id` | str? | 省略则复用/新建 dashboard 会话 |
| `session_key` | str? | 省略则用该 user+agent 的 dashboard key |
| `default_model` / `model` | str? | 如 `openai/gpt-4o` |
| `reasoning_mode` | `auto\|enabled\|disabled`? | |
| `reasoning_effort` | str? | |
| **`conversation_mode`** | `ask\|plan\|craft`? | **这就是只读/可写的开关** |
| `delivery` | `auto\|steer\|follow_up`? | 空闲时 `auto` 即正常发送 |
| `client_pending_id` | str? | 客户端 UUID，用于关联本地占位消息 |
| `expected_turn_id` | str? | busy 时必填（来自 `turn.started`） |
| `hitl_policy` | `{mode: ask\|allow_all\|allow_tools, tools: []}`? | |
| `skills` / `mcp_servers` / `knowledge_base_ids` / `target_agent_ids` | list? | |

**`conversation_mode` 三种取值**（`chat/models.py:56-59` 原文注释）：

- `ask` = read-only（只读）
- `plan` = 可写 `plans/*.md`
- `craft` = 默认，完整读写

> 只读问题的根因就在这里，且 `threads` 表有独立的 `conversation_mode` 列（`infra/db/migrations/017_thread_conversation_mode.sql`），默认按 `craft` 处理（`chat/history.py:140,408`）。

### 4.2 服务端 → 客户端

回合生命周期（`infra/gateway/ws/ws_channel.py:169-345`）：

| `type` | 字段 |
|--------|------|
| `turn.started` | `turn_id`, `seq`, `phase:"streaming_text"`, `thread_id` |
| `turn.resumed` | `turn_id`, `seq`, `phase`, `thread_id` |
| `turn.ended` | `turn_id`, `thread_id`, `failed?`, `hitl_paused?` |

流式内容：`ws_channel.py:215` 把 harness chunk **原样**透传，所以字段与 harness 一致（`infra/gateway/process/stream_project.py:128-215`）：

| `type` | 字段 | 语义 |
|--------|------|------|
| `token` | `content`（str）, `node`? | 正文增量，**纯追加** |
| `reasoning` | `content`（str） | 思考增量，**纯追加** |
| `tool_call_chunk` | `index`, `name`, `args`, `display_name`? | 工具名与参数**都是增量片段**，需按 `index` 累积后拼接 |
| `tool_result` | `name`, `display_name`?, 结果体 | 工具执行结果 |
| `hitl_required` | `request{...}`, `request.pending_id` | 需要人工确认，回合在此挂起 |
| `state_update` | `node` 等 | 状态变化（用于分段） |
| `error` | `message` | 错误 |
| `done` | `conversation_mode`?, `pending_plan_path`? | 回合正常结束 |

> `tool_call_chunk` 的 `name` 与 `args` 都是**流式片段**（`stream_project.py:146-160` 对 name 做字符串累加），客户端必须按 `index` 缓冲。

订阅与转向（`ws.py:101-148`, `ws.py:591-762`, `ws_channel.py:260-275`）：

| `type` | 字段 |
|--------|------|
| `pong` | — |
| `turn_status` | `thread_id`, `active` |
| `steer.ack` | `status`, `reason`?, `turn_id`?, `pending_id`?, `client_pending_id`?, `message_id`?, `thread_id` |
| `steer.queued` | `seq`, `client_pending_id`, `pending_id`, `turn_id`, `queue_length`, `preview`, `thread_id` |
| `steer.rejected` | `seq`, `client_pending_id`, `reason`, `active_turn_id`, `thread_id`, `gateway_class` |
| `steer.drained` | `seq`, `client_pending_ids`, `pending_ids`, `turn_id`, `message_id`, `thread_id` |
| `steer.discarded` | `seq`, `client_pending_ids`, `pending_ids`, `turn_id`, `reason`, `thread_id` |
| `steer.removed` | `seq`, `client_pending_id`, `pending_id`, `turn_id`, `already_drained`?, `already_gone`?, `thread_id` |
| `user` | `content`, `message_id`, `meta:{steer:true}`, `thread_id`, `attachments`? |
| `attachment` | `kind`(image\|video\|audio\|file), `mime_type`, `data`(base64), `filename`?, `alt_text`? |

**关于我此前逆向的两个错误，此处更正**：

- ❌ `usage` 事件 —— **不存在**。用量只在服务端内部统计（`usage_record.py`），WS 上不发（全仓 grep 无 `"type": "usage"`）。
- ❌ `state_snapshot` —— **未找到**。连接后不会拿到全量状态快照；「这个线程现在是否在跑」只能靠 `subscribe` 后返回的 `turn_status.active`。

### 4.3 通知 WebSocket

`WS /api/notifications/ws?token=`（`chat/notify_ws.py:23-26`）—— 用于 cron / 主动关怀推送。**本版不实现**。

---

## 5. REST

### 5.1 Agent

| 方法 | 路径 | 依据 |
|------|------|------|
| GET | `/api/agents` | `routers/agents.py` |
| GET | `/api/agents/{agent_id}` | 同上 |

`agent_id` 是**字符串**业务标识（`agents` 表另有整数自增 `id`）。关键字段：`name`, `description`, `default_model`, `icon_url`, `color`, `enabled`, `welcome_message`（`infra/db/repos/agents.py`）。

### 5.2 会话（thread）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/agents/{agent_id}/threads?limit=50` | 列表 |
| POST | `/api/agents/{agent_id}/threads` | 新建（201） |
| GET | `/api/agents/{agent_id}/threads/{thread_id}/history?limit=&offset=&cursor=` | 历史 |
| GET | `/api/agents/{agent_id}/threads/{thread_id}/context-usage` | 上下文用量 |
| PATCH | `/api/agents/{agent_id}/threads/{thread_id}` | 改名 / 置顶 / 换模式 |
| DELETE | `/api/agents/{agent_id}/threads/{thread_id}` | 删除（204） |
| POST | `/api/agents/{agent_id}/threads/{thread_id}/read` | 标记已读（204） |
| POST | `/api/agents/{agent_id}/threads/{thread_id}/fork` | 从某条助手消息分叉（201） |
| PATCH | `/api/agents/{agent_id}/session` | 重绑 dashboard 会话 |

依据：`chat/history.py:101, 225, 294, 414, 431, 472, 523, 622`。

**新建会话**（`chat/history.py:225-242`）：

```
POST /api/agents/{agent_id}/threads          → 201
响应：{"thread_id": "thr_<ulid>", "session_key": "<agent_id>:dashboard:<user_id>:dm"}
```

语义是 `ThreadRegistry.reset()`（`infra/gateway/threads.py:316-360`）：**新建一条 thread，并把该 user+agent 的 dashboard 会话重绑到它**。两点后果必须知道：

- **旧会话不删**，仍在 `/threads` 列表里（只是不再是 dashboard 的绑定目标）；
- 同一账号的**其他客户端（网页版）会被切到新会话** —— 这是上游 `/new` 本身的行为，不是客户端引入的。

**列表项字段**（`chat/history.py:126-152`）：

```
thread_id, title, channel_type, session_key, last_active, created_at,
is_active, has_messages, pinned, model_ref, reasoning_mode,
reasoning_effort, conversation_mode, pending_plan_path, hitl_policy,
turn_active, awaiting_user, + thread_artifacts_payload(...)
```

`turn_active` = 回合仍在流式；`awaiting_user` = 卡在 HITL 等待人工。**这两个字段直接驱动列表上的状态标记。**

**PATCH 可写字段**（`chat/models.py:194-201` `RenameThreadBody`）：`title`, `pinned`, `model_ref`, `reasoning_mode`, `reasoning_effort`, `conversation_mode`, `hitl_policy`。

各写接口的状态码：`PATCH /threads/{thread_id}` 返回 **200 + 更新后的 thread 对象**（`chat/history.py:523-560`，不是 204）；`DELETE /threads/{thread_id}` 204；`POST /threads/{thread_id}/read` 204；`POST /threads/{thread_id}/fork` 201。

> `title` / `pinned` 传 `null` 表示「不改这个字段」（`chat/history.py:541-545`）。客户端靠 `Json.explicitNulls = false` 保证 null 不进请求体，所以「只置顶」的请求不会顺手把标题清空。

**⚠️ 列表项里没有 `unread_count`** —— 上游每个会话确实各自维护未读计数（`sessions.unread_count`，`infra/db/repos/sessions.py:240-250`：主动消息落进会话时 +1，`infra/gateway/gateway.py:423-427`），并且 `unread_counts_for_threads`（`infra/gateway/threads.py:504`）也实现了，但 **thread 列表路由不用它**，全仓没有任何 REST 路由按会话下发未读。只有 **agent 级聚合**会随 `GET /api/agents` 返回 `unread_count`（`routers/agents.py:90-96, 269, 284`）。清零点有两个：`POST /agents/{agent_id}/threads/{thread_id}/read`（单会话）与 `POST /agents/{agent_id}/read`（该 agent 全部会话）。

> **客户端结论**：会话列表的未读角标只能自己攒 —— 判据是 `last_active` 比本地记录新（服务端只在「有消息落进这个会话」时才推 `last_active`，所以这是个可靠的变化信号）；打开会话时本地清零，并顺手调一次 `POST …/read` 把服务端那份一起清掉，否则下次刷新会把旧值翻出来。
>
> 已知近似：用户**正开着**某个会话时到达的消息，会被下一次列表刷新记成未读（客户端看不到「谁在读」），直到再打开一次该会话才消失。不做进一步区分 —— 为这个误差引入本地读游标不值当。

**历史响应**（`chat/history.py:391-411`）：

```
thread_id, messages[...], has_more, limit, offset, next_cursor,
history_loading, history_status, history_retry_after_ms,
turn_active, hitl_pending, conversation_mode, pending_plan_path, hitl_policy
```

> ⚠️ **`history_loading: true` 时必须按 `history_retry_after_ms`（1500）轮询重试**。首次打开老会话时服务端在做历史投影，`messages` 会是空数组。这是最容易做成「聊天记录空白」的坑。

### 5.3 消息结构

`chat/serialize.py:921-961`：

```jsonc
// 助手 / 用户消息
{
  "role": "user|assistant",
  "id": "<message_id>",
  "content": [ /* blocks */ ],
  "created_at": 1792900437,
  "attachments": [...]        // 仅 user，来自 inbound attachments
}

// 工具消息（role == "tool"）
{
  "role": "tool",
  "id": "...",
  "content": [{"type":"tool_result","id":"<tool_call_id>","name":"...","output":"...","artifact":?, "error_code"?}],
  "agent_id": "..."           // 多 agent 场景
}
```

**block 类型**（`serialize.py:858, 875, 884, 944`）：

| `type` | 字段 |
|--------|------|
| `text` | `text` |
| `thinking` | `thinking` |
| `tool_use` | 由 `tool_calls` 生成（`serialize.py:966`） |
| `tool_result` | `id`, `name`, `output`, `artifact`?, `error_code`? |
| `image` / `image_url` / `file` | 附件相关，带 `workspace_path` 等 |

### 5.4 上下文压缩标记（轨迹接口）

**压缩没有任何 WS 事件** —— `src/octop/infra/gateway/ws/` 下不存在 `compacted` 帧。唯一来源是轨迹接口：

```
GET /api/agents/{agent_id}/threads/{thread_id}/trajectory
    ?kinds=compacted&limit=<1..200，默认 100>&before_seq=<游标>
Authorization: Bearer <jwt>
```

响应（`routers/chat/trajectory.py:60-64`）：

```jsonc
{
  "thread_id": "...",
  "events": [{
    "event_id": "...", "thread_id": "...", "agent_id": "...",
    "seq": 128,                 // 游标身份，页内递增
    "ts": 1792900437.512,       // ⚠️ epoch 秒，带小数
    "kind": "compacted",        // user|assistant|tool|context|compacted|system|unknown
    "turn_id": "...", "request_seq": 4, "is_error": false,
    "summary": "…",             // 列表视图已裁剪到 240 字符
    "payload": {"summarized_count": 12, "preserved_count": 3, "removed_tokens": 8123, "summary": "…", "file_path"?}
  }],
  "next_before_seq": 128,       // 继续往前翻的游标；null 表示到头
  "has_more": true
}
```

**翻页语义有个坑**（`trajectory.py:267-283`）：服务端实际取 `limit + 1` 条，返回时**丢掉第一帧当游标**，`next_before_seq` 取 `page[0].seq`。也就是说每页返回 `limit` 条（而非 `limit + 1`），客户端只需照 `has_more` / `next_before_seq` 往前走。

**列表视图会裁剪 payload**：`content` / `text` / `thinking` 被整个丢掉，`args` / `result` 截到 2000 字符，`summary` 截到 240 字符（`trajectory.py:37-43, 113-132`）。要完整载荷得走 `GET …/trajectory/events/{event_id}`。

**客户端用法**：`ts * 1000` 得到毫秒，与消息 `sortOrder` 同一量纲（历史消息为 `created_at * 1000 + index`），因此可以直接按时间把分隔线插进消息流。失败一律静默 —— 老实例没有该路由时是 404，不该影响会话本身。

### 5.5 自动任务（服务端 cron）

自动任务的**真值在服务端**：执行由服务端 APScheduler 完成，客户端只做「镜像 + 增删改」。因此客户端**没有**任何本地定时器，也不需要 `WorkManager`。

```
GET    /api/agents/{agent_id}/cron               → 裸数组
POST   /api/agents/{agent_id}/cron               → 201，返回新建的 task
GET    /api/agents/{agent_id}/cron/{cron_id}     → 单个 task
PATCH  /api/agents/{agent_id}/cron/{cron_id}     → 部分更新
DELETE /api/agents/{agent_id}/cron/{cron_id}     → 204
POST   /api/agents/{agent_id}/cron/{cron_id}/run-now → 204（旁路触发一次，不改排期）
Authorization: Bearer <jwt>
```

**两个必须注意的点**：

1. `GET …/cron` 返回的是**裸数组**，不是 `{items: [...]}`；
2. **没有权限时返回空数组，而不是 403**（`routers/cron.py:117-122`）。所以「列表为空」既可能是真的没有任务，也可能是这个令牌没有 cron 权限 —— 客户端不能把空列表当成错误。

DTO 来自 `CronJobRow.to_public_dict`（`infra/db/repos/cron.py:108-126`）：

```jsonc
{
  "id": "...", "name": "每日早报", "trigger": "cron:0 9 * * *",
  "prompt": "…", "session_key": null, "model": null,
  "fresh_thread": true, "enabled": true, "task_type": "cron",
  "mcp_servers": [], "last_run_at": 1792900437.0, "last_status": "ok",
  "agent_id": "...", "last_error": null      // include_agent=True 时才有
}
```

`trigger` 有四种形态，由 `infra/cron/trigger.py:build_trigger` 解析：

| 形态 | 例子 | 含义 |
|------|------|------|
| `cron:<分 时 日 月 周>` | `cron:0 9 * * *` | Unix crontab 语义，按服务端时区的挂钟时间 |
| `interval:<秒>` | `interval:3600` | 每 N 秒一次 |
| `date:<ISO>` | `date:2030-01-01T08:00:00` | 一次性，跑过即止 |
| `agently:<连接器实例 id>` | `agently:abc123` | 由外部事件（如新邮件）触发 |

**客户端约定**：

- 编辑器**直接让用户填规则原文**，不在客户端造一套规则 DSL —— 服务端是唯一解析方，两边各存一份只会多出一处不一致。
- 「下次执行时间」由 `data/repo/ScheduleRules.kt` **本地估算**，只用于展示，不参与任何逻辑：服务端按自己的 `default_timezone` 解释挂钟时间，与设备时区不一致时会差几小时。
- 所有写操作遵循「服务端先成功再改本地镜像」。本地表是纯缓存，可以整体重建。
- 空列表走 `deleteByAgent` 而不是 `NOT IN ()` —— 后者在 SQLite 上是语法错的写法。

---

## 6. 附件

**上传**：`POST /api/agents/{agent_id}/upload`，multipart 字段名 **`file`**（`routers/uploads.py:59-83`）。

响应（`uploads.py:42-56`）：

```json
{
  "path": "inbound/xxx.png",
  "workspace_path": "inbound/xxx.png",
  "url": "<预览 URL>",
  "access_url": "<预览 URL>",
  "filename": "xxx.png",
  "media_type": "image/png"
}
```

上传后用于发送的形态：把附件放进 `user_turn.messages` 的 content block，带 `workspace_path` + `filename` + `media_type`（`chat/ws.py:523-558` `_steer_blocks_from_turn` 明确了服务端读的字段名）。

**下载 / 预览**：`GET /api/agents/{agent_id}/media/preview`、`GET /api/agents/{agent_id}/workspace/download`（`routers/workspace.py`）。预览 URL 由 `dashboard_inbound_preview_url()` 生成。

**`minis://` 类自定义协议：未找到** —— Octop 不使用自定义 scheme，附件就是 HTTP URL / `workspace_path`。

---

## 6.1 HITL 确认（SSE，不在 WS 上）

```
POST /api/agents/{agent_id}/chat/hitl/resume
Content-Type: application/json
响应：text/event-stream
```

请求体（`chat/models.py:204-223` `HitlResumeBody`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `thread_id` | str | 必填，挂起等待确认的会话 |
| `decisions` | list[dict] | 必填，≥1 条。`[{"type":"approve"}]` / `[{"type":"reject","message":"…"}]` / `[{"type":"respond","message":"…"}]` / `[{"type":"edit","edited_action":{…}}]` |
| `pending_id` | str? | 多张确认卡同时打开时必填 |
| `hitl_policy` | obj? | 本次续跑前应用的线程级工具放行策略 |

依据：`chat/routes.py:227-289`。

**流格式**（`chat/sse.py:24-32`）：每帧 `event: chunk\ndata: <JSON>\n\n`，`data` 里的对象**与 WS 帧同构**（`token` / `reasoning` / `tool_call_chunk` / `tool_result` / `hitl_required` / `error` / `done`），因此客户端可以复用同一个事件解码器。

**⚠️ 关键：续跑的增量只在 SSE 上，不推 WS hub**（`routes.py:155-183` 直接 `yield` chunk）。挂着 WS 也收不到，必须由客户端消费这条 SSE。`done` 帧带 `hitl_paused: true` 表示又挂起了新的确认卡。

---

## 7. 能力探测与降级判定点

因为 `/api/health` 无版本号，只能用「调用成功与否」判定：

| # | 探测 | 判据 | 失败动作 |
|---|------|------|---------|
| 1 | `GET /api/health` | HTTP 200 且 `ok==true` | 报具体原因（DNS/TLS/非 Octop） |
| 2 | `POST /api/auth/login` | 200 且有 `access_token` | 停在登录页显示 `AUTH_FAILED`/`SETUP_REQUIRED`/`LOGIN_LOCKED` |
| 3 | `GET /api/agents` | 200 且为非空数组 | 提示「该实例没有可用的 agent」 |
| 4 | `GET /api/agents/{id}/threads?limit=1` | 200 且为数组 | → `webview_only` |
| 5 | `WS /api/agents/{id}/chat/ws` + 发 `subscribe` | 收到 `turn_status` | 重试 1 次 → `webview_only` |

任一项判定 `webview_only`：整实例改用 WebView 兜底通道并持久化该判定。

---

## 8. 客户端实现备忘（易错点）

1. `token` 走 query 参数进 WS，不能只放 header。
2. `X-Octop-Access-Token` 响应头要回写本地，否则会莫名掉登录。
3. `tool_call_chunk` 的 `name`/`args` 是片段，按 `index` 累积。
4. `history_loading=true` 要轮询，不是空会话。
5. `turn.ended` 才代表回合真正结束；`done` 是流结束标记，两者都要收（`steer` 续跑时会有多段）。
6. 断线重连后必须重新 `subscribe`，并先读 `turn_status.active` 判断是否还在跑。
7. 发送时带上 `client_pending_id`（本地 UUID），服务端会在 `steer.*` 帧里回传，用于把本地占位消息对上。
8. 回合进行中再发送需带 `expected_turn_id`（来自 `turn.started`），否则会被判为 steer 拒绝。
9. **正常回合不会回显用户消息**：`user` 帧只在 steer 合并时下发（`steer.drained` → `_steer_user_frame`）。客户端必须自己先落一条本地用户行，否则发出去的消息在屏幕上会「消失」直到历史重拉。
10. **HITL 续跑的流在 SSE 上**（§6.1），不在 WS。要看到确认后的输出，必须消费 `POST …/chat/hitl/resume` 的 `event: chunk` 流。
11. `tool_call_chunk` 的 `name` 与 `args` 是片段，`index` 才是一条工具调用的身份；`display_name` 是给人看的名字，优先展示它。
12. **`turn.ended` 里没有「被取消」这个字段**：实到字段只有 `turn_id` / `thread_id` / `failed?` / `hitl_paused?`（`infra/gateway/ws/ws_channel.py:337-342`）。所以「用户按了中止」这条信息只能客户端自己记 —— 收到它那一轮的 `turn.ended` 时据此把本地那一行标成中断，而不是 `done`（spec §19 第 11 条）。
13. **`token` 帧不带任何消息身份**（只有 `content` / `node?`），一条流式行对应哪一轮完全靠客户端自己按回合建行。因此本地流式行的 id 必须与 `turn_id` 绑定（`local-<turn_id>`），断流重连后服务端若仍在跑同一轮，续到的 token 才会写回**同一行**而不是另起一条。
