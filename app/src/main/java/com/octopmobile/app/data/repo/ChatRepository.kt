package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.MessageDao
import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.MessageStatus
import com.octopmobile.app.data.db.PendingOpDao
import com.octopmobile.app.data.db.PendingOpEntity
import com.octopmobile.app.data.db.ThreadDao
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.model.HitlPending
import com.octopmobile.app.data.model.Part
import com.octopmobile.app.data.model.PartType
import com.octopmobile.app.data.model.PartsCodec
import com.octopmobile.app.data.model.UploadedAttachment
import com.octopmobile.app.data.model.parseHitlPending
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.BlockType
import com.octopmobile.app.data.remote.dto.ContentBlockDto
import com.octopmobile.app.data.remote.dto.HitlDecision
import com.octopmobile.app.data.remote.dto.HitlResumeBody
import com.octopmobile.app.data.remote.dto.MessageDto
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.data.ws.ClientFrame
import com.octopmobile.app.data.ws.ServerEvent
import com.octopmobile.app.data.ws.UserContentBlock
import com.octopmobile.app.data.ws.UserMessage
import com.octopmobile.app.data.ws.isError
import com.octopmobile.app.data.ws.outputText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.UUID

/**
 * 单个会话的**读写**通路（docs/spec.md §6 `data/repo`）。
 *
 * 三件事在这里合流，都写进同一张 `messages` 表：
 * - **历史**（REST）：进页面时拉一页最近的消息，上滑再往前翻；
 * - **增量**（WS）：`subscribe` 之后收到的 `token` / `reasoning` / 工具事件落到一条本地行上；
 * - **发送**（WS）：`user_turn` 先落本地用户行再发（上游正常回合**不回显**用户消息）。
 *
 * HITL 确认是唯一的例外：它的续跑增量在 `POST …/chat/hitl/resume` 的 **SSE** 上，不推 WS hub，
 * 所以 [resolveHitl] 自己消费那条流，把帧喂进同一个 [apply] 管线（docs/protocol.md §6.1）。
 */
class ChatRepository(
    private val agentId: String,
    private val threadId: String,
    private val api: OctopApi,
    private val socket: ChatSocket,
    private val messageDao: MessageDao,
    private val threadDao: ThreadDao,
    private val pendingOpDao: PendingOpDao,
    private val json: Json,
) {

    /** 链路状态，UI 用来显示「连接中 / 已断开，正在重连」。 */
    val connection: StateFlow<ChatSocket.State> = socket.state

    /** 服务端是否正在跑回合。唯一来源是 `subscribe` 后的 `turn_status`（docs/protocol.md §4.2）。 */
    private val _turnActive = MutableStateFlow(false)
    val turnActive: StateFlow<Boolean> = _turnActive.asStateFlow()

    /** 服务端下发的错误帧。M1 只做提示，不做重试。 */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** 正在等人工确认的卡片。非空时回合被服务端挂起，UI 必须禁用输入（docs/protocol.md §6.1）。 */
    private val _hitl = MutableStateFlow<HitlPending?>(null)
    val hitl: StateFlow<HitlPending?> = _hitl.asStateFlow()

    fun observeMessages(): Flow<List<MessageEntity>> = messageDao.observeByThread(threadId)

    /** 会话元数据（标题、模式、是否在跑）。由列表页回填过，这里只读不改。 */
    fun observeThread(): Flow<ThreadEntity?> = threadDao.observeOne(threadId)

    /**
     * 连接并持续消费事件，**永不返回** —— 由调用方的 scope 决定生命周期。
     *
     * 必须先 `connect()` + 开始 `collect` 再 `subscribe`：事件走 `SharedFlow(replay=0)`，
     * 没有订阅者时帧会被直接丢弃。
     */
    suspend fun run() {
        socket.connect()
        socket.subscribe(threadId)
        coroutineScope {
            // 每次连上补发断网期间排队的帧（首次连上时通常为空，docs/spec.md §10）。
            launch {
                socket.state.collect { if (it is ChatSocket.State.Open) replayPending() }
            }
            socket.events.collect { apply(it) }
        }
    }

    fun close() {
        socket.close()
    }

    // ------------------------------------------------------------ 发送

    /**
     * 发送一轮输入。
     *
     * 上游正常回合**不回显**用户消息 —— `ws_channel.py:413-425` 是全仓唯一发 `user` 帧的地方，
     * 只在 steer 合并时触发。所以本地必须先落一条用户行，否则界面上这轮输入会「断头」。
     *
     * 返回 false 表示 socket 还没连上，帧已进 `pending_ops`，连上后由 [replayPending] 补发。
     */
    suspend fun sendTurn(
        text: String,
        attachments: List<UploadedAttachment> = emptyList(),
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return true

        appendLocalUser(trimmed, attachments)
        return deliver(buildUserTurn(trimmed, attachments))
    }

    /** 中断当前回合。回合已经结束时服务端会回一帧 `error`，只需提示，不需要重试。 */
    suspend fun cancel(): Boolean = deliver(ClientFrame.Cancel(threadId))

    /**
     * 上传附件到 agent workspace，返回可直接塞进 `user_turn` 的引用（docs/protocol.md §6）。
     *
     * 正文与附件不能并存于 `text`：上游看到非空 `text` 就丢掉 `messages`，所以附件只能走
     * content block —— 见 [buildUserTurn]。
     */
    suspend fun upload(
        filename: String,
        mediaType: String,
        bytes: ByteArray,
    ): ApiResult<UploadedAttachment> =
        when (val result = api.upload(agentId, filename, mediaType, bytes)) {
            is ApiResult.Failure -> result

            is ApiResult.Ok -> {
                val dto = result.value
                // `workspace_path` 是发送时要回传的那个；老版本只给 `path`，兜一下。
                val workspacePath = dto.workspacePath ?: dto.path
                if (workspacePath.isNullOrBlank()) {
                    ApiResult.Failure(ApiError.Parse("upload response has no workspace_path"))
                } else {
                    ApiResult.Ok(
                        UploadedAttachment(
                            workspacePath = workspacePath,
                            filename = dto.filename ?: filename,
                            mediaType = dto.mediaType ?: mediaType,
                        ),
                    )
                }
            }
        }

    /**
     * 提交人工确认，并消费续跑流。
     *
     * 续跑的增量**只在**这条 SSE 上 —— `chat/routes.py:155-183` 是直接 `yield`，服务端不往
     * WS hub 推（docs/protocol.md §6.1），所以这里自己把 `data:` 帧解成 [ServerEvent] 喂进
     * 同一个 [apply] 管线。
     *
     * [pendingId] 留空时服务端按 thread 解析当前挂起项（`chat/routes.py:239-245`）—— 首轮
     * 挂起的卡片本来就没有 `pending_id`。
     */
    suspend fun resolveHitl(
        decisions: List<HitlDecision>,
        pendingId: String? = null,
    ) {
        if (decisions.isEmpty()) return
        val previous = _hitl.value
        _hitl.value = null
        _turnActive.value = true
        try {
            api.hitlResume(
                agentId = agentId,
                body = HitlResumeBody(
                    threadId = threadId,
                    decisions = decisions,
                    pendingId = pendingId,
                ),
            ).collect { raw -> apply(ServerEvent.decode(json, raw)) }
        } catch (e: IOException) {
            // 没提交成功：把卡片放回去让用户重试，而不是假装已经处理了。
            _hitl.value = previous
            _errors.tryEmit(e.message ?: e.javaClass.simpleName)
        } finally {
            _turnActive.value = false
        }
    }

    private fun buildUserTurn(
        text: String,
        attachments: List<UploadedAttachment>,
    ): ClientFrame.UserTurn {
        val pendingId = CLIENT_PENDING_PREFIX + UUID.randomUUID().toString().take(8)
        if (attachments.isEmpty()) {
            return ClientFrame.UserTurn(
                text = text,
                threadId = threadId,
                delivery = DELIVERY_AUTO,
                clientPendingId = pendingId,
            )
        }
        // 带附件必须走 `messages`：上游看到非空 `text` 就把它包成一条 user 消息、丢掉
        // `messages`（`chat/models.py:86-92`），所以正文要放进 content block 里。
        val blocks = buildList {
            if (text.isNotEmpty()) add(UserContentBlock(type = UserContentBlock.TEXT, text = text))
            attachments.forEach { add(it.toBlock()) }
        }
        return ClientFrame.UserTurn(
            messages = listOf(UserMessage(content = blocks)),
            threadId = threadId,
            delivery = DELIVERY_AUTO,
            clientPendingId = pendingId,
        )
    }

    /** 本地先落一条用户行，历史回来时会被 [loadHistory] 的占位清理与服务端那条合并。 */
    private suspend fun appendLocalUser(text: String, attachments: List<UploadedAttachment>) {
        val parts = buildList {
            if (text.isNotEmpty()) add(Part(type = PartType.TEXT, text = text))
            attachments.forEach {
                add(Part(type = PartType.ATTACHMENT, filename = it.filename, mime = it.mediaType))
            }
        }
        if (parts.isEmpty()) return
        messageDao.upsertStreaming(
            id = LOCAL_PREFIX + "user-" + UUID.randomUUID(),
            threadId = threadId,
            turnId = null,
            role = ROLE_USER,
            sortOrder = nextSortOrder(),
            partsJson = PartsCodec.encode(json, parts),
            status = MessageStatus.DONE,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 发一帧；socket 不在线就转存进 `pending_ops`（`kind` 只用于排查问题）。 */
    private suspend fun deliver(frame: ClientFrame): Boolean {
        if (socket.send(frame)) return true
        pendingOpDao.upsert(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                kind = if (frame is ClientFrame.Cancel) KIND_CANCEL else KIND_SEND,
                threadId = threadId,
                payloadJson = json.encodeToString(ClientFrame.serializer(), frame),
                retryCount = 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return false
    }

    /**
     * 按入队顺序补发（docs/spec.md §10）。发出去就删，发失败就停下等下次重连 ——
     * 宁可下轮再发，也不要在一条没通的连接上空转。
     */
    private suspend fun replayPending() {
        for (op in pendingOpDao.all()) {
            if (op.threadId != threadId) continue
            val frame = runCatching {
                json.decodeFromString(ClientFrame.serializer(), op.payloadJson)
            }.getOrNull()
            if (frame == null) {
                // 解不出来说明是旧版本写下的帧，删掉比反复重试更有用。
                pendingOpDao.delete(op.id)
                continue
            }
            if (!socket.send(frame)) {
                pendingOpDao.bumpRetry(op.id)
                return
            }
            pendingOpDao.delete(op.id)
        }
    }

    // ------------------------------------------------------------ 历史

    /**
     * 拉一页历史。`offset` 是**从最新往前的条数**（上游 `infra/history/reader.py:read_page`
     * 里 `stop = len(raw) - offset`），页内按时间正序返回，`has_more` 表示还有更早的。
     *
     * `history_loading == true` 时服务端在整理历史投影，此时 `messages` 一定是空数组，
     * 必须轮询（docs/protocol.md §5.2）—— 这是「老会话打开空白」的根因。
     */
    suspend fun loadHistory(
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): ApiResult<HistoryPage> {
        var attempt = 0
        while (true) {
            when (val result = api.history(agentId, threadId, limit, offset)) {
                is ApiResult.Failure -> return result

                is ApiResult.Ok -> {
                    val page = result.value
                    if (page.historyLoading) {
                        attempt++
                        if (attempt >= MAX_PROJECTION_ATTEMPTS) {
                            // 放弃轮询：返回「还在整理」，让 UI 说明情况，而不是装作这会话是空的。
                            return ApiResult.Ok(HistoryPage(count = 0, hasMore = false, loading = true))
                        }
                        delay(page.historyRetryAfterMs.takeIf { it > 0 } ?: DEFAULT_RETRY_MS)
                        continue
                    }

                    val entities = page.messages.mapIndexed { index, dto ->
                        dto.toEntity(index, threadId, json)
                    }
                    if (entities.isNotEmpty()) messageDao.upsertAll(entities)

                    // 服务端已经有这些消息了，本地那份流式期间落下的占位行就该退场，否则重复两条。
                    // 只在真的拿到消息时才清理：全新会话（历史为空）不能把刚流完的行抹掉。
                    if (offset == 0 && entities.isNotEmpty()) {
                        messageDao.deletePlaceholders(threadId, MessageStatus.DONE, LOCAL_PREFIX)
                        messageDao.deletePlaceholders(threadId, MessageStatus.FAILED, LOCAL_PREFIX)
                    }

                    return ApiResult.Ok(
                        HistoryPage(count = entities.size, hasMore = page.hasMore, loading = false),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ 增量事件

    /** 当前正在流式的本地行。`token` 只带 `content`，要对上一条消息只能靠我们自己按回合建行。 */
    private var live: Live? = null
    private var currentTurnId: String? = null
    private var lastFlushAt = 0L

    private class Live(
        val id: String,
        val turnId: String?,
        val sortOrder: Long,
        val parts: MutableList<Part> = mutableListOf(),
        /** `tool_call_chunk.index` → [parts] 里的下标：同一个工具调用的片段要落在同一个 part 上。 */
        val toolCalls: MutableMap<Int, Int> = mutableMapOf(),
    )

    private suspend fun apply(event: ServerEvent) {
        when (event) {
            is ServerEvent.TurnStarted -> {
                currentTurnId = event.turnId
                _turnActive.value = true
                // 新回合开始 = 上一张确认卡已经处理完（可能是续跑后再次挂起，随后会再推一帧）。
                _hitl.value = null
            }

            is ServerEvent.TurnEnded -> {
                // 回合真正结束的是这一帧，`done` 只是当前这段流结束（steer 续跑会有多段）。
                _turnActive.value = false
                currentTurnId = null
                live?.let { persist(it, if (event.failed) MessageStatus.FAILED else MessageStatus.DONE, force = true) }
                live = null
            }

            is ServerEvent.Token -> {
                val row = ensureLive()
                row.append(PartType.TEXT, event.content)
                persist(row, MessageStatus.STREAMING, force = false)
            }

            is ServerEvent.Reasoning -> {
                val row = ensureLive()
                row.append(PartType.REASONING, event.content)
                persist(row, MessageStatus.STREAMING, force = false)
            }

            is ServerEvent.Done -> {
                // 这段流结束了，但回合可能还会被 steer 续上：留着一个 DONE 的行，
                // 下一批 token 到达时会自动翻回 STREAMING。
                live?.let { persist(it, MessageStatus.DONE, force = true) }
            }

            is ServerEvent.TurnStatus -> _turnActive.value = event.active

            is ServerEvent.UserEcho -> appendEchoedUser(event)

            is ServerEvent.Error -> {
                _errors.tryEmit(event.message)
                live?.let { persist(it, MessageStatus.FAILED, force = true) }
            }

            is ServerEvent.ToolCallChunk -> {
                // name 与 args 都是**流式片段**，按 `index` 累加（docs/protocol.md §8 第 3 条）。
                val row = ensureLive()
                row.appendToolCall(event)
                persist(row, MessageStatus.STREAMING, force = false)
            }

            is ServerEvent.ToolResult -> {
                val row = ensureLive()
                row.parts += Part(
                    type = PartType.TOOL_RESULT,
                    name = event.name,
                    output = event.outputText,
                    errorCode = if (event.isError) TOOL_STATUS_ERROR else null,
                )
                persist(row, MessageStatus.STREAMING, force = true)
            }

            is ServerEvent.HitlRequired -> {
                // 回合在这里挂起：正文收尾，卡片交给 UI，等用户确认（续跑的输出走 SSE，见
                // [resolveHitl]）。不切断 [live] —— 续跑的输出接着落在同一行上。
                _hitl.value = parseHitlPending(event.request)
                live?.let { persist(it, MessageStatus.DONE, force = true) }
            }

            is ServerEvent.Attachment,
            is ServerEvent.State,
            is ServerEvent.Steer,
            is ServerEvent.Unknown,
            ServerEvent.Pong,
            -> Unit
        }
    }

    private suspend fun ensureLive(): Live {
        live?.let { return it }
        val sortOrder = nextSortOrder()
        val id = LOCAL_PREFIX + (currentTurnId ?: sortOrder.toString())
        return Live(id = id, turnId = currentTurnId, sortOrder = sortOrder).also { live = it }
    }

    /**
     * 服务端回显的用户消息（可能是网页端发的）。字段只有 `content`，按纯文本块落一行。
     * 带 `message_id` 时会与稍后历史里的同 id 行合并（`@Upsert`），不会重复。
     */
    private suspend fun appendEchoedUser(event: ServerEvent.UserEcho) {
        val text = event.content
        if (text.isNullOrEmpty()) return
        val sortOrder = nextSortOrder()
        messageDao.upsertStreaming(
            id = event.messageId ?: (LOCAL_PREFIX + "user-" + sortOrder),
            threadId = threadId,
            turnId = null,
            role = ROLE_USER,
            sortOrder = sortOrder,
            partsJson = PartsCodec.encode(json, listOf(Part(type = PartType.TEXT, text = text))),
            status = MessageStatus.DONE,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 本地行的排序键。取「当前时刻」与「已有最大值 + 1」中较大者，保证永远排在历史之后。 */
    private suspend fun nextSortOrder(): Long =
        maxOf(System.currentTimeMillis(), (messageDao.maxSortOrder(threadId) ?: 0L) + 1)

    /**
     * 落库。流式期间每个 token 都写一次数据库太浪费，按 [FLUSH_INTERVAL_MS] 节流；
     * 内存里的 `parts` 始终是最新的，回合结束/中断时 [force] 补一次完整写入。
     */
    private suspend fun persist(row: Live, status: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAt < FLUSH_INTERVAL_MS) return
        lastFlushAt = now
        messageDao.upsertStreaming(
            id = row.id,
            threadId = threadId,
            turnId = row.turnId,
            role = ROLE_ASSISTANT,
            sortOrder = row.sortOrder,
            partsJson = PartsCodec.encode(json, row.parts),
            status = status,
            updatedAt = now,
        )
    }

    /** 增量只追加到同类型的最后一段上，避免每个 token 生成一个 part。 */
    private fun Live.append(type: String, content: String) {
        val last = parts.lastOrNull()
        if (last?.type == type) {
            parts[parts.lastIndex] = last.copy(text = (last.text ?: "") + content)
        } else {
            parts += Part(type = type, text = content)
        }
    }

    /**
     * 同一个工具调用的片段累加到同一个 part 上。
     *
     * 上游对 `name` 也是字符串累加（`stream_project.py:141-160`），所以这里照做 —— 首个片段
     * 建 part，后续片段拼接。`index` 是身份，不是 [parts] 的下标。
     */
    private fun Live.appendToolCall(chunk: ServerEvent.ToolCallChunk) {
        val at = toolCalls[chunk.index]
        if (at == null) {
            toolCalls[chunk.index] = parts.size
            parts += Part(type = PartType.TOOL_CALL, name = chunk.name, args = chunk.args)
            return
        }
        val part = parts[at]
        parts[at] = part.copy(
            name = part.name.orEmpty() + chunk.name,
            args = (part.args ?: "") + chunk.args,
        )
    }

    data class HistoryPage(val count: Int, val hasMore: Boolean, val loading: Boolean)

    private companion object {
        const val PAGE_SIZE = 30

        /** 历史投影轮询上限（约 1 分钟），超过就放弃并把「还在整理」如实告诉 UI。 */
        const val MAX_PROJECTION_ATTEMPTS = 40
        const val DEFAULT_RETRY_MS = 1500L

        /** 流式落库节流间隔。M3 会换成按帧合并渲染，这里先保证不刷爆数据库。 */
        const val FLUSH_INTERVAL_MS = 120L

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 本地生成行的 id 前缀：历史回来后靠它区分「服务端已有」与「只在我们这儿」。 */
        const val LOCAL_PREFIX = "local-"

        /** 空闲时发送用 `auto`（服务端据此判定是新回合而不是 steer）。 */
        const val DELIVERY_AUTO = "auto"

        /** `client_pending_id` 前缀，纯本地可读性用途（服务端原样回传）。 */
        const val CLIENT_PENDING_PREFIX = "cp-"

        /** `pending_ops.kind` 取值。 */
        const val KIND_SEND = "send"
        const val KIND_CANCEL = "cancel"

        /** [Part.errorCode] 标记工具执行失败，UI 用红字。 */
        const val TOOL_STATUS_ERROR = "error"
    }
}

/**
 * 历史消息 → 本地行。
 *
 * `sortOrder = createdAt * 1000 + index`：与本地实时行（`System.currentTimeMillis()`）同一量纲，
 * 因此两批消息混在一起也能正确排序。同一秒内的多条消息靠 `index` 拉开。
 */
private fun MessageDto.toEntity(index: Int, threadId: String, json: Json): MessageEntity {
    val seconds = createdAt ?: 0L
    return MessageEntity(
        // 极少数消息没有 id（不该发生）：用一个不撞 `local-` 前缀的兜底 id，
        // 免得刚写进去就被 loadHistory 的占位清理顺手删掉。
        id = id ?: "hist-fallback-$seconds-$index",
        threadId = threadId,
        turnId = null,
        role = role,
        sortOrder = seconds * 1000 + index,
        partsJson = PartsCodec.encode(json, content.mapNotNull { it.toPart() }),
        status = MessageStatus.DONE,
        errorInfo = null,
        streamInterruptCount = 0,
        updatedAt = System.currentTimeMillis(),
    )
}

private fun ContentBlockDto.toPart(): Part? = when (type) {
    BlockType.TEXT -> Part(type = PartType.TEXT, text = text)
    BlockType.THINKING -> Part(type = PartType.REASONING, text = thinking)
    BlockType.TOOL_USE -> Part(type = PartType.TOOL_CALL, name = name)
    BlockType.TOOL_RESULT -> Part(
        type = PartType.TOOL_RESULT,
        name = name,
        output = output,
        errorCode = errorCode,
    )

    BlockType.IMAGE, BlockType.IMAGE_URL, BlockType.FILE -> Part(
        type = PartType.ATTACHMENT,
        filename = filename,
        mime = mediaType,
        url = url,
    )

    else -> null
}
