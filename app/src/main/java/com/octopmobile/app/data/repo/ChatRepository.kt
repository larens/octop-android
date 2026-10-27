package com.octopmobile.app.data.repo

import com.octopmobile.app.data.attachments.AttachmentRefs
import com.octopmobile.app.data.attachments.AttachmentStore
import com.octopmobile.app.data.db.AttachmentDao
import com.octopmobile.app.data.db.AttachmentEntity
import com.octopmobile.app.data.db.AttachmentState
import com.octopmobile.app.data.db.CompactMarkerDao
import com.octopmobile.app.data.db.CompactMarkerEntity
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
import com.octopmobile.app.data.remote.dto.TrajectoryKind
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.data.ws.ClientFrame
import com.octopmobile.app.data.ws.ServerEvent
import com.octopmobile.app.data.ws.UserContentBlock
import com.octopmobile.app.data.ws.UserMessage
import com.octopmobile.app.data.ws.isError
import com.octopmobile.app.data.ws.outputText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
    threadId: String,
    /** 当前实例地址。落库的附件引用是相对的（不含主机名），发请求时用它补全（spec §13）。 */
    private val baseUrl: String,
    private val api: OctopApi,
    private val socket: ChatSocket,
    private val messageDao: MessageDao,
    private val threadDao: ThreadDao,
    private val pendingOpDao: PendingOpDao,
    private val attachmentDao: AttachmentDao,
    private val attachmentStore: AttachmentStore,
    private val compactMarkerDao: CompactMarkerDao,
    private val json: Json,
) {

    /**
     * 当前绑定的会话 id。新会话先用 `draft-…` 占位（不碰服务端），第一条消息发出前由
     * [bindServerThread] 换成服务端返回的真实 id —— 这样不发消息的「新会话」永远不会在
     * 服务端留下空记录（策略见 [ThreadRepository.createLocalDraft]）。
     */
    private val _threadId = MutableStateFlow(threadId)
    private val threadId: String get() = _threadId.value
    val boundThreadId: String get() = threadId

    private val _serverEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 32)
    val serverEvents: SharedFlow<ServerEvent> = _serverEvents.asSharedFlow()

    /** 本地草稿（还没有服务端身份）。UI 层据此决定先建服务端会话再发首条消息。 */
    val isDraft: Boolean get() = ThreadRepository.isDraftId(threadId)

    /**
     * 后台作用域：附件下载与轨迹同步。
     *
     * 这两件事都绝不能占用事件收集那条链路 —— 一次网络往返会让它后面的所有帧排队等待，
     * 正文的流式输出在手感上直接断掉。
     */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在下载的附件 id。历史与 WS 常常各报一次同一个附件，靠它去重。 */
    private val inFlight = mutableSetOf<String>()

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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMessages(): Flow<List<MessageEntity>> =
        _threadId.flatMapLatest { messageDao.observeByThread(it) }

    /** 会话元数据（标题、模式、是否在跑）。由列表页回填过，这里只读不改。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeThread(): Flow<ThreadEntity?> =
        _threadId.flatMapLatest { threadDao.observeOne(it) }

    /** 本地附件缓存索引，UI 据此判断某个附件能不能直接内联渲染（spec §13）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAttachments(): Flow<List<AttachmentEntity>> =
        _threadId.flatMapLatest { attachmentDao.observeByThread(it) }

    /**
     * 打开会话即已读（spec §11）：本地清零 + 服务端 `POST …/threads/{id}/read` 清 0。
     *
     * 列表页的未读角标是客户端自己攒的（服务端列表接口不下发 `unread_count`，见
     * docs/protocol.md §5.2），所以进会话页要把这两份都清掉 —— 少清一份，下次刷新又会冒出来。
     * 服务端那次失败静默：界面上的未读已经消了，不该因为一个计数接口把提示推到用户脸上。
     */
    suspend fun markRead() {
        threadDao.markRead(threadId)
        // 草稿在服务端还不存在，别拿假 id 去请求。
        if (!isDraft) api.markThreadRead(agentId, threadId)
    }

    /** 上下文压缩点，按时间正序，UI 据此在消息流里插分隔线（spec §8.2）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCompactMarkers(): Flow<List<CompactMarkerEntity>> =
        _threadId.flatMapLatest { compactMarkerDao.observeByThread(it) }

    /**
     * 把草稿会话绑到服务端建好的真实会话上（首条消息发送前调用）。
     *
     * 换 id 时把本地各表里挂着草稿 id 的行一起迁过去 —— 这样聊天页的 Room 流不用重建，
     * 列表抽屉里的这条会话也保持同一条记录。草稿期因为还没发过消息，这些表通常是空的，
     * 统一迁移只是兜住边界情况（比如断网时已经排队的帧）。
     */
    suspend fun bindServerThread(serverThreadId: String) {
        val draft = _threadId.value
        if (draft == serverThreadId) return
        messageDao.moveThread(draft, serverThreadId)
        attachmentDao.moveThread(draft, serverThreadId)
        pendingOpDao.moveThread(draft, serverThreadId)
        compactMarkerDao.moveThread(draft, serverThreadId)
        threadDao.moveThread(draft, serverThreadId)
        _threadId.value = serverThreadId
        socket.unsubscribe(draft)
        socket.subscribe(serverThreadId)
    }

    /**
     * 连接并持续消费事件，**永不返回** —— 由调用方的 scope 决定生命周期。
     *
     * 必须先 `connect()` + 开始 `collect` 再 `subscribe`：事件走 `SharedFlow(replay=0)`，
     * 没有订阅者时帧会被直接丢弃。
     */
    suspend fun run() {
        socket.connect()
        // 草稿在服务端还不存在，订阅会收到一条 thread-not-found 错误帧；等
        // bindServerThread 拿到真实 id 后再订阅（重连所需的订阅集合由 ChatSocket 维护）。
        if (!isDraft) socket.subscribe(threadId)
        coroutineScope {
            // 每次连上补发断网期间排队的帧（首次连上时通常为空，docs/spec.md §10）。
            launch {
                socket.state.collect { state ->
                    when (state) {
                        is ChatSocket.State.Open -> replayPending()

                        // 链路断了：正在流式的那一行就此定格（spec §10「半截消息」）。
                        // **保留** `live` 而不是丢掉 —— 重连后如果服务端说这一轮还在跑，
                        // 后续 token 要接着写回同一行（见 [ensureLive] 的 id 规则）。
                        is ChatSocket.State.Closed -> interruptLive()

                        is ChatSocket.State.Connecting,
                        ChatSocket.State.Idle,
                        -> Unit
                    }
                }
            }
            socket.events.collect { apply(it) }
        }
    }

    fun close() {
        socket.close()
        background.cancel()
    }

    /** 应用回前台时让退避中的重连立刻发生，不等下一次等待（spec §10）。 */
    fun reconnectNow() = socket.reconnectNow()

    // ------------------------------------------------------------ 发送

    /**
     * 发送一轮输入。
     *
     * 上游正常回合**不回显**用户消息 —— `ws_channel.py:413-425` 是全仓唯一发 `user` 帧的地方，
     * 只在 steer 合并时触发。所以本地必须先落一条用户行，否则界面上这轮输入会「断头」。
     *
     * [targetAgentIds] 是 `@专家`（spec §23.5）要拉进本轮的其它 agent，只有 `user_turn`
     * 帧能带（上游没有按回合切换角色的机制，见 [ClientFrame.UserTurn]）。
     *
     * 返回 false 表示 socket 还没连上，帧已进 `pending_ops`，连上后由 [replayPending] 补发。
     */
    suspend fun sendTurn(
        text: String,
        attachments: List<UploadedAttachment> = emptyList(),
        targetAgentIds: List<String> = emptyList(),
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return true

        appendLocalUser(trimmed, attachments)
        return deliver(buildUserTurn(trimmed, attachments, targetAgentIds))
    }

    /**
     * 中断当前回合。回合已经结束时服务端会回一帧 `error`，只需提示，不需要重试。
     *
     * 记下这是**用户主动**中止：`turn.ended` 只有 `failed` / `hitl_paused`，**没有**「被取消」
     * 这个字段，所以收尾时只能靠本地这个标记决定那一行是 `interrupted` 还是 `done`
     * （spec §19 第 11 条）。
     */
    suspend fun cancel(): Boolean {
        cancelRequested = true
        return deliver(ClientFrame.Cancel(threadId))
    }

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
        targetAgentIds: List<String>,
    ): ClientFrame.UserTurn {
        val pendingId = CLIENT_PENDING_PREFIX + UUID.randomUUID().toString().take(8)
        val targets = targetAgentIds.takeIf { it.isNotEmpty() }
        if (attachments.isEmpty()) {
            return ClientFrame.UserTurn(
                text = text,
                threadId = threadId,
                targetAgentIds = targets,
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
            targetAgentIds = targets,
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
                        dto.toEntity(agentId, index, threadId, json)
                    }
                    if (entities.isNotEmpty()) messageDao.upsertAll(entities)

                    // 服务端已经有这些消息了，本地那份流式期间落下的占位行就该退场，否则重复两条。
                    // 只在真的拿到消息时才清理：全新会话（历史为空）不能把刚流完的行抹掉。
                    //
                    // `interrupted` 也一起清：那一轮的内容服务端已经记下了。UI 上的
                    // 「重新拉取该轮」走的也是这条路径（重拉 `offset = 0` 这一页），
                    // 靠这一步把本地那半截行换成服务端的版本，而不是留下两条重复气泡。
                    if (offset == 0 && entities.isNotEmpty()) {
                        messageDao.deletePlaceholders(threadId, MessageStatus.DONE, LOCAL_PREFIX)
                        messageDao.deletePlaceholders(threadId, MessageStatus.FAILED, LOCAL_PREFIX)
                        messageDao.deletePlaceholders(threadId, MessageStatus.INTERRUPTED, LOCAL_PREFIX)
                        messageDao.deleteMissingServerMessagesSince(
                            threadId = threadId,
                            serverIds = entities.map { it.id },
                            minSortOrder = entities.minOf { it.sortOrder },
                            prefix = LOCAL_PREFIX,
                        )
                    }

                    return ApiResult.Ok(
                        HistoryPage(count = entities.size, hasMore = page.hasMore, loading = false),
                    )
                }
            }
        }
    }

    /** 服务端先删真实 checkpoint；成功后才移除本地行，避免只在界面上隐藏错误上下文。 */
    suspend fun deleteConversationTurn(anchorMessageId: String, localMessageIds: List<String>): ApiResult<Unit> {
        if (isDraft || anchorMessageId.startsWith(LOCAL_PREFIX) || localMessageIds.isEmpty()) {
            return ApiResult.Failure(ApiError.Parse("conversation turn is not persisted yet"))
        }
        return when (val result = api.deleteConversationTurn(agentId, threadId, anchorMessageId)) {
            is ApiResult.Failure -> result
            is ApiResult.Ok -> {
                attachmentDao.deleteByMessageIds(threadId, localMessageIds.distinct())
                messageDao.deleteByIds(threadId, localMessageIds.distinct())
                result
            }
        }
    }

    // ------------------------------------------------------------ 增量事件

    /** 当前正在流式的本地行。`token` 只带 `content`，要对上一条消息只能靠我们自己按回合建行。 */
    private var live: Live? = null
    private var currentTurnId: String? = null
    private var lastFlushAt = 0L

    /** [live] 是否已经被标成中断。链路反复断开时只该记一次，否则计数很快变成噪声。 */
    private var liveInterrupted = false

    /** 用户是否按过「中止」，等它那一轮的 `turn.ended` 来消费（见 [cancel]）。 */
    private var cancelRequested = false

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
                // 上一轮的「中止」意图到此为止：它能生效的时机只有它自己那一轮的 `turn.ended`
                // （离线时那帧排进了 `pending_ops`，服务端多半只会回一句「没有在跑的回合」）。
                cancelRequested = false
            }

            is ServerEvent.TurnEnded -> {
                // 回合真正结束的是这一帧，`done` 只是当前这段流结束（steer 续跑会有多段）。
                _turnActive.value = false
                currentTurnId = null
                live?.let { row ->
                    when {
                        // 用户主动中止 —— 服务端没有这个信号，只能靠 [cancel] 留下的标记。
                        cancelRequested -> row.finalizeInterrupted()
                        else -> persist(
                            row,
                            if (event.failed) MessageStatus.FAILED else MessageStatus.DONE,
                            force = true,
                        )
                    }
                }
                live = null
                liveInterrupted = false
                cancelRequested = false
                // 压缩发生在回合之间，而且没有 WS 事件 —— 只有在这里主动问一次，长任务跑到
                // 一半被压缩时界面上才会当场出现分隔线，而不是等用户重开会话。
                background.launch { syncCompactMarkers() }
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

            is ServerEvent.TurnStatus -> {
                _turnActive.value = event.active
                // 重连后的校正（spec §10）：服务端说这一轮已经不在跑了，那本地这条半截行就
                // 到此为止 —— 必须丢掉 `live`，否则下一轮的 token 会接着写进这条旧行
                // （`token` 帧不带任何消息身份，只有 `live` 的生命周期能区分是哪一轮）。
                if (!event.active && liveInterrupted) {
                    live = null
                    liveInterrupted = false
                }
            }

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

            is ServerEvent.Attachment -> cacheAttachment(event)

            is ServerEvent.State,
            is ServerEvent.Steer,
            is ServerEvent.Unknown,
            ServerEvent.Pong,
            -> Unit
        }
        _serverEvents.emit(event)
    }

    // ------------------------------------------------------------ 附件

    /**
     * 把一帧 `attachment` 变成一份本地缓存（docs/spec.md §13）。
     *
     * 上游两种形态都走这里：带 `data`（base64 完整文件）的直接落盘，带 `url` / `path` 的
     * 先记一行索引再后台下载。
     *
     * 这里**刻意不往消息气泡里塞 part** —— 历史投影会把同一个附件再报一次，两处都加就是
     * 重复的两个气泡。消息里的附件由 [ContentBlockDto.toPart] 归一化出引用，UI 渲染时
     * 按需调 [ensureAttachment]。
     */
    private suspend fun cacheAttachment(event: ServerEvent.Attachment) {
        val payload = event.data?.let { AttachmentRefs.decodeBase64(it) }
        val hasRemote = !event.url.isNullOrBlank() ||
            !event.previewUrl.isNullOrBlank() ||
            !event.path.isNullOrBlank()

        val ref = when {
            hasRemote -> AttachmentRefs.canonical(
                agentId = agentId,
                url = event.url ?: event.previewUrl,
                workspacePath = event.path,
            )

            payload != null -> AttachmentRefs.inlineRef(payload)
            else -> null
        } ?: return

        val id = AttachmentRefs.idOf(threadId, ref)
        if (payload != null) {
            // 帧里已经带着完整文件了，直接落盘，不必再走一次网络。
            if (attachmentDao.find(id)?.state != AttachmentState.CACHED) {
                attachmentStore.store(threadId, id, ref, event.mimeType, payload)
            }
            return
        }
        // 只自动拉图片：其余附件在界面上只显示文件名，拉下来没有任何用处，
        // 却可能把一个大视频塞进缓存。
        if (event.kind == KIND_IMAGE || AttachmentRefs.isImage(event.mimeType)) {
            requestDownload(id, ref, event.mimeType)
        }
    }

    /**
     * UI 渲染到某个远端附件时按需拉取（spec §19 第 18 条「附件二次打开命中本地缓存」）。
     *
     * [ref] 是 [AttachmentRefs.canonical] 归一化后的相对引用，来自消息 part 的 `url`。
     */
    suspend fun ensureAttachment(ref: String?, mime: String?) {
        if (ref.isNullOrBlank()) return
        val id = AttachmentRefs.idOf(threadId, ref)
        if (!beginDownload(id)) return
        try {
            download(id, ref, mime)
        } finally {
            inFlight.remove(id)
        }
    }

    private fun requestDownload(id: String, ref: String, mime: String?) {
        background.launch {
            if (!beginDownload(id)) return@launch
            try {
                download(id, ref, mime)
            } finally {
                inFlight.remove(id)
            }
        }
    }

    /** 已经缓存、或已经在下载的，不再重复触发。并发去重靠 [inFlight]。 */
    private suspend fun beginDownload(id: String): Boolean {
        if (attachmentDao.find(id)?.state == AttachmentState.CACHED) return false
        return synchronized(inFlight) {
            if (id in inFlight) {
                false
            } else {
                inFlight += id
                true
            }
        }
    }

    private suspend fun download(id: String, ref: String, mime: String?) {
        // 内联合成引用（`inline:`）没有远端地址，只能读缓存。
        val absolute = AttachmentRefs.absolute(baseUrl, ref) ?: return
        val existing = attachmentDao.find(id)

        // 先落一行「只有远端」的索引：下载失败时 UI 至少知道这个附件存在，不会去找一个
        // 不存在的本地文件。
        attachmentDao.upsertAll(
            listOf(
                AttachmentEntity(
                    id = id,
                    threadId = threadId,
                    messageId = existing?.messageId,
                    remoteUrl = ref,
                    localPath = null,
                    mime = mime ?: existing?.mime,
                    size = 0,
                    state = AttachmentState.REMOTE_ONLY,
                ),
            ),
        )

        when (val result = api.download(absolute)) {
            is ApiResult.Ok -> attachmentStore.store(threadId, id, ref, mime, result.value)
            is ApiResult.Failure -> attachmentDao.markFailed(id)
        }
    }

    // ------------------------------------------------------------ 上下文压缩标记

    /**
     * 同步上下文压缩点（docs/spec.md §8.2）。
     *
     * 压缩**没有任何 WS 事件**，轨迹接口是唯一来源：`GET …/trajectory?kinds=compacted`
     * （`routers/chat/trajectory.py:239-283`）。事件里 `ts` 是 epoch 秒，转成毫秒后与
     * 消息的 `sortOrder` 同一量纲，UI 就能按时间把它插进消息流。
     *
     * 接口按 `before_seq` 从新往旧翻页，压缩又是稀疏事件，所以顺着游标翻几页就够；
     * 翻页上限只是防止某个一直不返回 `has_more=false` 的实现在这儿空转。
     *
     * 失败一律**静默**：这条数据只是消息流上的一条分隔线，不值得为它弹错误、更不该
     * 影响会话本身。老实例根本没有这条路由（404）也走这条路径。
     */
    suspend fun syncCompactMarkers() {
        var beforeSeq: Int? = null
        repeat(MAX_MARKER_PAGES) {
            val page = when (
                val result = api.trajectory(
                    agentId = agentId,
                    threadId = threadId,
                    kinds = TrajectoryKind.COMPACTED,
                    limit = MARKER_PAGE_SIZE,
                    beforeSeq = beforeSeq,
                )
            ) {
                is ApiResult.Ok -> result.value
                is ApiResult.Failure -> return
            }

            // `kinds` 是服务端过滤，这里再挡一次：万一实例忽略了该参数，不至于把
            // 普通对话事件也当成压缩点画进消息流。
            page.events
                .filter { it.kind == TrajectoryKind.COMPACTED }
                .forEach { event ->
                    compactMarkerDao.upsert(
                        CompactMarkerEntity(
                            threadId = threadId,
                            createdAt = (event.ts * 1000).toLong(),
                            summary = event.summary.orEmpty(),
                        ),
                    )
                }

            val next = page.nextBeforeSeq
            if (!page.hasMore || next == null) return
            beforeSeq = next
        }
    }

    /**
     * 建立（或复用）这一轮的本地流式行。
     *
     * id 用 `local-<turnId>`：**这是「断流续写」能成立的关键** —— 重连后服务端仍在跑同一轮时，
     * 新 token 会落到同一个 id 上，把刚才标成中断的那一行翻回 `streaming` 继续接，
     * 而不是另起一条重复气泡（spec §10）。
     */
    private suspend fun ensureLive(): Live {
        live?.let { return it }
        val sortOrder = nextSortOrder()
        val id = LOCAL_PREFIX + (currentTurnId ?: sortOrder.toString())
        return Live(id = id, turnId = currentTurnId, sortOrder = sortOrder)
            .also {
                live = it
                liveInterrupted = false
            }
    }

    // ------------------------------------------------------------ 半截消息（spec §10）

    /**
     * 链路断了：把正在流式的那一行定格成「输出中断」。
     *
     * [live] 保持不动 —— 重连后要是服务端说这一轮还在跑，后续 token 会接着写回同一行
     *（见 [ensureLive] 的 id 规则）。
     */
    private suspend fun interruptLive() {
        val row = live ?: return
        if (liveInterrupted) return
        row.finalizeInterrupted()
    }

    /**
     * 收尾上个进程留下的「僵尸流式行」。
     *
     * 进程被杀时 [live] 只存在于内存里，库里那一行就永远停在 `streaming`，界面上会一直
     * 显示成正在输出。进页面时扫一遍把它们标成中断。误伤是不可能的：服务端如果还在跑这一轮，
     * 重连后的 token 会写回同一个 id 并把它翻回 `streaming`。
     */
    suspend fun markStaleInterrupted() {
        messageDao.markStaleInterrupted(
            threadId = threadId,
            from = listOf(MessageStatus.STREAMING, MessageStatus.PENDING),
            to = MessageStatus.INTERRUPTED,
            prefix = LOCAL_PREFIX,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 落最后一段正文 + 标记中断 + 计数 +1，一条语句完成（见 `MessageDao.markInterrupted`）。 */
    private suspend fun Live.finalizeInterrupted() {
        liveInterrupted = true
        messageDao.markInterrupted(
            id = id,
            status = MessageStatus.INTERRUPTED,
            partsJson = PartsCodec.encode(json, parts),
            updatedAt = System.currentTimeMillis(),
        )
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
     * 落库。token 是逐字到达的，每个都写一次库会同时压满 SQLite 与重组，所以按
     * [FRAME_INTERVAL_MS] 合并成一帧再落库（spec §12）；内存里的 `parts` 始终是最新的，
     * 回合结束/中断时 [force] 补一次完整写入，因此丢掉的只是中间帧、不是最终状态。
     */
    private suspend fun persist(row: Live, status: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAt < FRAME_INTERVAL_MS) return
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

        /**
         * 流式合帧间隔（spec §12「token 到达后按 ~50ms 合帧」）。
         *
         * 上游 token 是逐字下发的，攒够一帧再落库，UI 也就跟着 ~20fps 更新 —— 既省掉大量
         * 无意义的数据库写入，也不会让每一帧都触发一次重组。
         */
        const val FRAME_INTERVAL_MS = 50L

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

        /** `attachment` 事件的 `kind` 取值之一（docs/protocol.md §4.2）。 */
        const val KIND_IMAGE = "image"

        /** 轨迹接口单页条数（服务端上限 200，`trajectory.py:32-33`）。 */
        const val MARKER_PAGE_SIZE = 100

        /** 压缩标记的翻页上限。压缩是稀疏事件，正常一页就到底。 */
        const val MAX_MARKER_PAGES = 4
    }
}

/**
 * 历史消息 → 本地行。
 *
 * `sortOrder = timestamp + index`：与本地实时行（`System.currentTimeMillis()`）同一量纲，
 * 因此两批消息混在一起也能正确排序。同一毫秒内的多条消息靠 `index` 拉开。
 *
 * 服务端给的是**毫秒**（`timestamp`），不是秒，所以这里不要再乘 1000。只有老实例/异常路径
 * 拿不到 `timestamp` 时才退回 `created_at`（秒）——那时 `sortOrder` 会退化成 `0 + index`，
 * 排序仍按服务端顺序，但「一轮耗时」这类需要真实时间差的展示拿不到值。
 */
private fun MessageDto.toEntity(agentId: String, index: Int, threadId: String, json: Json): MessageEntity {
    val at = timestampMs ?: (createdAt?.times(1000) ?: 0L)
    return MessageEntity(
        // 极少数消息没有 id（不该发生）：用一个不撞 `local-` 前缀的兜底 id，
        // 免得刚写进去就被 loadHistory 的占位清理顺手删掉。
        id = id ?: "hist-fallback-$at-$index",
        threadId = threadId,
        turnId = null,
        role = role,
        sortOrder = at + index,
        partsJson = PartsCodec.encode(json, content.mapNotNull { it.toPart(agentId) }),
        status = MessageStatus.DONE,
        errorInfo = null,
        streamInterruptCount = 0,
        // 历史行取服务端时间（与行序一致）而不是「写入本地的时刻」：拉一次历史会重写
        // `updatedAt`，用本地时刻会让这一列跟消息本身毫无关系。副作用是历史行的
        // `updatedAt == sortOrder`，UI 据此区分「服务端行」与「本地流式行」（见 `thinkingDurationMs`）。
        updatedAt = at,
    )
}

/**
 * 内容块 → part。
 *
 * 附件块在这里就把 `url` / `workspace_path` **归一化成不含主机名的相对引用**
 * （docs/spec.md §13）：换域名后本地缓存仍然对得上，UI 也能用同一个键查缓存。
 * `image_url` 块的 `url` 嵌在子对象里、顶层拿不到，但同一块上带着 `workspace_path`，
 * 归一化会退回用它拼下载地址。
 */
private fun ContentBlockDto.toPart(agentId: String): Part? = when (type) {
    BlockType.TEXT -> Part(type = PartType.TEXT, text = text)
    BlockType.THINKING -> Part(type = PartType.REASONING, text = thinking)
    BlockType.TOOL_USE -> Part(
        type = PartType.TOOL_CALL,
        name = name,
        // 入参在 `input` 上（`serialize.py:842-864`）。空对象当成「没有参数」，免得折叠里
        // 多出一行 `参数 {}`；有值时按 [Part.args] 的约定存 JSON 文本。
        args = input?.takeIf { it !is JsonNull && it.toString() != EMPTY_JSON_OBJECT }?.toString(),
    )
    BlockType.TOOL_RESULT -> Part(
        type = PartType.TOOL_RESULT,
        name = name,
        output = output,
        errorCode = errorCode,
    )

    BlockType.IMAGE, BlockType.IMAGE_URL, BlockType.FILE -> {
        val ref = AttachmentRefs.canonical(agentId, url, workspacePath)
        // 既没有可下载的引用、也没有文件名时，这个块在界面上没有任何可展示的东西。
        if (ref == null && filename.isNullOrBlank()) {
            null
        } else {
            Part(
                type = PartType.ATTACHMENT,
                filename = filename,
                // 图片块的 mime 在 `mime_type` 上；再兜一层「块类型本身说明它是图片」。
                mime = mediaType ?: mimeType
                    ?: IMAGE_MIME.takeIf { type == BlockType.IMAGE || type == BlockType.IMAGE_URL },
                url = ref,
            )
        }
    }

    else -> null
}

/** 图片块缺 mime 时的兜底值，只用来让 UI 走「内联渲染」而不是「文件名卡片」。 */
private const val IMAGE_MIME = "image/*"

/** `JsonObject` 的紧凑串形式，用来识别「没有参数」的空对象。 */
private const val EMPTY_JSON_OBJECT = "{}"
