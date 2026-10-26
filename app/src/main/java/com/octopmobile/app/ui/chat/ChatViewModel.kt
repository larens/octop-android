package com.octopmobile.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.db.AttachmentEntity
import com.octopmobile.app.data.db.CompactMarkerEntity
import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.model.HitlPending
import com.octopmobile.app.data.model.UploadedAttachment
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.data.remote.dto.HitlDecision
import com.octopmobile.app.data.remote.dto.ResolvedModelDto
import com.octopmobile.app.data.repo.ChatRepository
import com.octopmobile.app.data.repo.ConversationTurn
import com.octopmobile.app.data.repo.ThreadRepository
import com.octopmobile.app.data.repo.conversationTurnFor
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.ui.appContainer
import com.octopmobile.app.ui.AutoCreatedConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ChatUiState(
    /** 历史投影中（服务端在整理，`messages` 暂时为空 —— protocol.md §5.2）。 */
    val historyLoading: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    /** REST 失败（历史分页）。 */
    val error: ApiError? = null,
    /** WS 下发的 `error` 帧原文。 */
    val streamError: String? = null,
    val modelsLoading: Boolean = false,
    val modelOptions: List<ChatModelOption> = emptyList(),
    val selectedModelRef: String? = null,
)

data class ChatModelOption(
    val modelRef: String,
    val label: String,
    val providerName: String,
    val reasoning: Boolean,
)

/**
 * 单会话流式（docs/spec.md §18 M1/M2）。
 *
 * 消息一律从 Room 读（[ChatRepository] 负责把历史、WS 增量、HITL 续跑合流写库），所以：
 * - 进页面先看到上次留下的内容，不空转；
 * - 流式输出的每一次落库都会自动推动 UI 重绘，不需要在这里手工拼接字符串。
 *
 * 「回合进行中」由 [turnActive] 表达，UI 据此把输入框置灰、发送按钮换成中止
 * （docs/spec.md §18.1：本轮不做 steer）。
 */
class ChatViewModel(
    private val container: AppContainer,
    private val agentId: String,
    private val threadId: String,
) : ViewModel() {

    private var repository: ChatRepository? = null
    private var threadRepository: ThreadRepository? = null
    private var baseUrl: String? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 草稿 → 服务端会话只建一次：并发点发送/分享时排队等，第二路直接复用已绑定的 id。 */
    private val draftBindMutex = Mutex()

    /**
     * 仓库是否可用（[repository] 已赋值且首屏历史已处理完）。
     *
     * [repository] 是 `init` 里异步拿到的 —— 要先从 DataStore 读 host。快捷方式 / 分享
     * 交给会话页的那一发可能在它之前到，所以这个信号得显式暴露出去让 UI 排队等，
     * 而不是让 [share] 撞上 null 把内容默默吞掉。
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 已从服务端拉回的消息条数，即下一次 `/history` 的 `offset`（从最新往前计数）。 */
    private var nextOffset = 0

    var uiState by mutableStateOf(ChatUiState())
        private set

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _thread = MutableStateFlow<ThreadEntity?>(null)
    val thread: StateFlow<ThreadEntity?> = _thread.asStateFlow()

    private val _connection = MutableStateFlow<ChatSocket.State>(ChatSocket.State.Idle)
    val connection: StateFlow<ChatSocket.State> = _connection.asStateFlow()

    private val _turnActive = MutableStateFlow(false)
    val turnActive: StateFlow<Boolean> = _turnActive.asStateFlow()

    /** 待人工确认的卡片。非空时输入框禁用 —— 服务端在挂起期间会拒绝新回合（phase=hitl_paused）。 */
    private val _hitl = MutableStateFlow<HitlPending?>(null)
    val hitl: StateFlow<HitlPending?> = _hitl.asStateFlow()

    /** 已上传、等随下一条消息一起发出的附件。 */
    private val _attachments = MutableStateFlow<List<UploadedAttachment>>(emptyList())
    val attachments: StateFlow<List<UploadedAttachment>> = _attachments.asStateFlow()

    /**
     * 可以 `@` 的专家：`GET /api/agents`（`scope=mine` = 自己的 + 别人共享的），
     * 去掉当前会话这个 agent 自己 —— `target_agent_ids` 是「把**别的**专家拉进本轮」，
     * `@` 自己没有意义（spec §23.5）。
     *
     * 它同时是发送时把正文里的 `@名字` 还原成 `agent_id` 的唯一凭据（见 [mentionTargets]）。
     */
    private val _mentionable = MutableStateFlow<List<AgentDto>>(emptyList())
    val mentionable: StateFlow<List<AgentDto>> = _mentionable.asStateFlow()

    /**
     * 本地附件缓存索引，按 [com.octopmobile.app.data.attachments.AttachmentRefs.idOf] 的 id 索引。
     *
     * 气泡里的附件先在这里查本地文件，命中就直接内联渲染（spec §19 第 18 条），
     * 没命中才由 [ensureAttachment] 去拉。
     */
    private val _cachedAttachments = MutableStateFlow<Map<String, AttachmentEntity>>(emptyMap())
    val cachedAttachments: StateFlow<Map<String, AttachmentEntity>> = _cachedAttachments.asStateFlow()

    /** 上下文压缩点，按时间正序。消息流里的分隔线由它决定插在哪两条消息之间（spec §8.2）。 */
    private val _compactMarkers = MutableStateFlow<List<CompactMarkerEntity>>(emptyList())
    val compactMarkers: StateFlow<List<CompactMarkerEntity>> = _compactMarkers.asStateFlow()

    /** 附件上传中。 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _deletingTurnIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingTurnIds: StateFlow<Set<String>> = _deletingTurnIds.asStateFlow()

    /** 一次性提示（离线排队、上传失败等）。文案在 UI 侧解析 —— 这里拿不到 `stringResource`。 */
    private val _notice = MutableStateFlow<ChatNotice?>(null)
    val notice: StateFlow<ChatNotice?> = _notice.asStateFlow()

    init {
        viewModelScope.launch {
            val instanceHost = container.prefs.currentHost()
            if (instanceHost.isNullOrBlank()) {
                uiState = uiState.copy(error = ApiError.Network("no host"))
                return@launch
            }

            val repo = container.chatRepository(instanceHost, agentId, threadId)
            val threadRepo = container.threadRepository(instanceHost, agentId)
            baseUrl = instanceHost
            repository = repo
            threadRepository = threadRepo

            launch {
                repo.observeMessages().collect {
                    _messages.value = it
                    // 第一条消息落地（本地回显，见 ChatRepository.sendTurn）= 这轮互动算数，
                    // 自动新建的会话从此转为永久保留。
                    if (it.isNotEmpty()) AutoCreatedConversation.consume(threadId)
                }
            }
            launch { repo.observeThread().collect { _thread.value = it } }
            launch { repo.connection.collect { _connection.value = it } }
            launch { repo.turnActive.collect { _turnActive.value = it } }
            launch { repo.hitl.collect { _hitl.value = it } }
            launch { repo.errors.collect { uiState = uiState.copy(streamError = it) } }
            launch {
                repo.observeAttachments().collect { list ->
                    _cachedAttachments.value = list.associateBy { it.id }
                }
            }
            launch { repo.observeCompactMarkers().collect { _compactMarkers.value = it } }

            // `@专家` 候选：一次只读请求，失败就当作「没有别的专家」—— 不打扰用户，
            // 输入栏也只是不出现那个入口（spec §23.5）。
            launch { loadMentionable(instanceHost) }
            launch { loadModels(instanceHost, threadRepo) }

            // 永不返回：连接 + 订阅 + 持续消费事件
            launch { repo.run() }

            loadFirstPage()

            // 首屏处理完再放行，避免分享写进去的本地行被 `loadHistory` 的占位清理当成残留删掉。
            _ready.value = true
        }
    }

    override fun onCleared() {
        // 断开不会取消服务端正在跑的回合（protocol.md §4.2），但本地必须收掉 socket 与重连循环
        repository?.close()
    }

    /**
     * 新开的会话只在真的产生交互后才有价值。用户离开时仍没有任何消息，
     * 就把服务端和本地那份一起删掉，避免“新会话”堆成一串无意义空项。
     */
    fun deleteUntouchedAutoConversation() {
        if (!shouldDeleteUntouchedConversation(
                isNewConversation = AutoCreatedConversation.isTracked(threadId),
                hasMessages = _messages.value.isNotEmpty(),
            )
        ) {
            return
        }
        AutoCreatedConversation.consume(threadId)
        val host = baseUrl ?: return
        cleanupScope.launch {
            container.threadRepository(host, agentId).delete(threadId)
        }
    }

    fun loadOlder() {
        val repo = repository ?: return
        if (uiState.loadingOlder || !uiState.hasMore) return

        uiState = uiState.copy(loadingOlder = true)
        viewModelScope.launch {
            when (val result = repo.loadHistory(offset = nextOffset)) {
                is ApiResult.Ok -> {
                    nextOffset += result.value.count
                    uiState = uiState.copy(
                        loadingOlder = false,
                        hasMore = result.value.hasMore,
                        error = null,
                    )
                }

                is ApiResult.Failure -> uiState = uiState.copy(
                    loadingOlder = false,
                    error = result.error,
                )
            }
        }
    }

    fun retry() {
        viewModelScope.launch { loadFirstPage() }
    }

    /**
     * 应用回到前台：让退避中的重连立刻发生（spec §10）。
     *
     * 不处理「已经连着但其实断了」的情况 —— 那是 socket 心跳静默判定的职责。
     */
    fun reconnect() {
        repository?.reconnectNow()
    }

    // ------------------------------------------------------------ 发送 / 中止 / 确认

    /**
     * 发送一轮输入。附件在发送时随帧一起走，所以这里先清空待发列表 ——
     * 即使 socket 离线被排进 `pending_ops`，帧里也已经带着它们了。
     *
     * `@专家` 不在这里存一份状态：它就在正文里（[mentionTargets]），把那几个字删掉就等于取消。
     */
    fun send(text: String) {
        val repo = repository ?: return
        val files = _attachments.value
        if (text.isBlank() && files.isEmpty()) return

        val targets = mentionTargets(text)

        // 草稿：先 `POST /threads` 拿服务端 id（这就是用户说的「请求有回复后再拉会话 ID」），
        // 把草稿绑定上去，才真正发这一轮。建失败就留着输入和附件，只提示错误。
        if (repo.isDraft) {
            viewModelScope.launch {
                if (!bindDraftIfNeeded(repo)) return@launch
                _attachments.value = emptyList()
                _notice.value = null
                val queued = !repo.sendTurn(text, files, targets)
                loadFirstPage()
                if (queued) _notice.value = ChatNotice.Queued
            }
            return
        }

        _attachments.value = emptyList()
        _notice.value = null
        viewModelScope.launch {
            if (!repo.sendTurn(text, files, targets)) _notice.value = ChatNotice.Queued
        }
    }

    /**
     * 从正文里认出 `@专家名`，还原成帧里的 `target_agent_ids`（spec §23.5）。
     *
     * 名字只从 [mentionable] 里认，所以「正文里恰好有个 `@xxx`」不会被误当成专家；而
     * `@` 是不是从候选列表里点进去的并不重要 —— 手打一个和候选同名的 `@名字` 同样生效，
     * 这正是 `@` 该有的行为。名字里的正则字符不必转义，这里是纯文本包含判断。
     */
    private fun mentionTargets(text: String): List<String> =
        _mentionable.value
            .filter { text.contains(MENTION_PREFIX + it.displayName()) }
            .map { it.agentId }
            .distinct()

    fun cancel() {
        val repo = repository ?: return
        viewModelScope.launch { repo.cancel() }
    }

    fun conversationTurn(selectedMessageId: String): ConversationTurn? =
        conversationTurnFor(_messages.value, selectedMessageId)

    fun deleteConversationTurn(turn: ConversationTurn) {
        val repo = repository ?: return
        if (_turnActive.value || !turn.isPersisted || _deletingTurnIds.value.isNotEmpty()) return
        _deletingTurnIds.value = turn.messageIds.toSet()
        _notice.value = null
        viewModelScope.launch {
            when (val result = repo.deleteConversationTurn(turn.anchorMessageId, turn.messageIds)) {
                is ApiResult.Ok -> {
                    nextOffset = (nextOffset - turn.messageIds.size).coerceAtLeast(0)
                    _notice.value = ChatNotice.TurnDeleted
                }
                is ApiResult.Failure -> _notice.value = ChatNotice.Failure(result.error)
            }
            _deletingTurnIds.value = emptySet()
        }
    }

    /**
     * 提交人工确认。上游要求决定条数与动作数**严格相等**（`hitl/coordinator.py:197-198`），
     * 所以一个动作一条，用同一个决定类型。
     */
    fun resolveHitl(decisionType: String, message: String? = null) {
        val repo = repository ?: return
        val pending = _hitl.value ?: return
        val decisions = pending.actions.map { HitlDecision(type = decisionType, message = message) }
        viewModelScope.launch { repo.resolveHitl(decisions, pending.pendingId) }
    }

    /** 读取选中的文件并上传。`uri` 来自系统文件选择器，读完即用，不落盘。 */
    fun attach(context: Context, uri: Uri) {
        val repo = repository ?: return
        if (_busy.value) return

        _busy.value = true
        _notice.value = null
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) { readAttachment(context, uri) }
            if (payload == null) {
                _busy.value = false
                _notice.value = ChatNotice.AttachFailed
                return@launch
            }

            when (val result = repo.upload(payload.name, payload.mime, payload.bytes)) {
                is ApiResult.Ok -> _attachments.value = _attachments.value + result.value
                is ApiResult.Failure -> _notice.value = ChatNotice.Failure(result.error)
            }
            _busy.value = false
        }
    }

    fun removeAttachment(attachment: UploadedAttachment) {
        _attachments.value = _attachments.value - attachment
    }

    /**
     * 把其他 App 分享进来的内容落地成一次发送（docs/spec.md §14、§19 第 21 条）。
     *
     * 附件逐个读、逐个传：分享来的 `content://` 常常没有长期读取权限，读不出来的直接跳过，
     * 不因为其中一个坏掉就把整条分享丢掉。只剩不下任何内容时才什么都不做。
     */
    fun share(context: Context, text: String?, uris: List<Uri>) {
        val repo = repository ?: return
        if (_busy.value) return

        _busy.value = true
        _notice.value = null
        viewModelScope.launch {
            val uploaded = mutableListOf<UploadedAttachment>()
            for (uri in uris) {
                val payload = withContext(Dispatchers.IO) { readAttachment(context, uri) } ?: continue
                when (val result = repo.upload(payload.name, payload.mime, payload.bytes)) {
                    is ApiResult.Ok -> uploaded += result.value
                    is ApiResult.Failure -> _notice.value = ChatNotice.Failure(result.error)
                }
            }
            _busy.value = false

            val body = text?.trim().orEmpty()
            if (body.isEmpty() && uploaded.isEmpty()) return@launch
            // 分享也是「首条消息」，同样要先建服务端会话（拿 id 绑定）再发。
            if (!bindDraftIfNeeded(repo)) return@launch
            if (!repo.sendTurn(body, uploaded)) _notice.value = ChatNotice.Queued
        }
    }

    /**
     * 草稿的首条消息发送前：在服务端建真实会话，把本地草稿行与仓库绑定过去。
     *
     * 返回 false 表示还没建出来（离线/服务端拒绝），调用方应保留输入内容、只提示错误。
     * 绑定成功后如果用户已在草稿期选过模型，补一次服务端 PATCH，让第一个回复就按所选跑。
     */
    private suspend fun bindDraftIfNeeded(repo: ChatRepository): Boolean = draftBindMutex.withLock {
        if (!repo.isDraft) return true
        val holder = threadRepository ?: return false
        when (val result = holder.createServerThread()) {
            is ApiResult.Ok -> {
                repo.bindServerThread(result.value)
                val wanted = uiState.selectedModelRef
                if (wanted != null) {
                    when (val modelResult = holder.setModel(result.value, wanted)) {
                        is ApiResult.Ok -> Unit
                        is ApiResult.Failure -> _notice.value = ChatNotice.Failure(modelResult.error)
                    }
                }
                true
            }

            is ApiResult.Failure -> {
                _notice.value = ChatNotice.Failure(result.error)
                false
            }
        }
    }

    /**
     * 气泡渲染到某个远端附件、而本地还没有那份文件时触发下载（spec §13）。
     *
     * [ref] 是消息 part 上那个已经归一化过的相对引用；没有引用（用户刚发出的本地附件、
     * 或内联帧）时直接返回 —— 那时界面上只剩文件名，没有可下载的东西。
     */
    fun ensureAttachment(ref: String?, mime: String?) {
        val repo = repository ?: return
        if (ref.isNullOrBlank()) return
        viewModelScope.launch { repo.ensureAttachment(ref, mime) }
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun selectModel(modelRef: String?) {
        val repo = threadRepository ?: return
        val normalized = modelRef?.takeIf { it.isNotBlank() }
        if (uiState.selectedModelRef == normalized) return
        viewModelScope.launch {
            // 草稿在服务端还不存在：选择先记在本地，绑定真实会话后再补 PATCH（见 send）。
            if (repository?.isDraft == true) {
                uiState = uiState.copy(selectedModelRef = normalized)
                return@launch
            }
            when (val result = repo.setModel(threadId, normalized)) {
                is ApiResult.Ok -> {
                    uiState = uiState.copy(selectedModelRef = normalized)
                }

                is ApiResult.Failure -> _notice.value = ChatNotice.Failure(result.error)
            }
        }
    }

    /** `@专家` 候选只拉一次。失败静默：这不是主链路，拉不到就只是没有这个入口。 */
    private suspend fun loadMentionable(baseUrl: String) {
        val result = container.instanceRepository(baseUrl).agents()
        if (result !is ApiResult.Ok) return
        _mentionable.value = result.value.filter { it.agentId != agentId }
    }

    private suspend fun loadModels(baseUrl: String, threadRepo: ThreadRepository) {
        uiState = uiState.copy(modelsLoading = true)
        val agentsResult = container.instanceRepository(baseUrl).agents()
        val modelsResult = threadRepo.resolvedModels()
        val currentAgent = (agentsResult as? ApiResult.Ok)?.value?.firstOrNull { it.agentId == agentId }
        val selected = _thread.value?.modelRef
            ?: currentAgent?.defaultModel
        if (modelsResult is ApiResult.Ok) {
            uiState = uiState.copy(
                modelsLoading = false,
                modelOptions = modelsResult.value.map { it.toChatOption() },
                selectedModelRef = selected,
            )
        } else {
            uiState = uiState.copy(modelsLoading = false, selectedModelRef = selected)
        }
    }

    private suspend fun loadFirstPage() {
        val repo = repository ?: return
        // 草稿没有服务端历史，也还没有可清的服务端未读；等绑定真实会话后再拉（见 [send]）。
        if (repo.isDraft) {
            uiState = uiState.copy(historyLoading = false, hasMore = false)
            return
        }
        uiState = uiState.copy(historyLoading = true, error = null)

        // 打开会话即已读（spec §11）。单独挂一个协程：它是一次多余的往返，
        // 不能让首屏历史等它。
        viewModelScope.launch { repo.markRead() }

        // 压缩标记只是消息流上的分隔线，不能拖慢首屏：跟着历史并行走，失败静默（见 repo）。
        viewModelScope.launch { repo.syncCompactMarkers() }

        // 上个进程被杀时留下的流式行要先收尾，否则界面会一直显示成正在输出（spec §10）。
        repo.markStaleInterrupted()

        when (val result = repo.loadHistory(offset = 0)) {
            is ApiResult.Ok -> {
                nextOffset = result.value.count
                uiState = uiState.copy(
                    historyLoading = false,
                    hasMore = result.value.hasMore,
                )
            }

            is ApiResult.Failure -> uiState = uiState.copy(
                historyLoading = false,
                error = result.error,
            )
        }
    }

    companion object {
        fun factory(agentId: String, threadId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ChatViewModel(appContainer(), agentId, threadId) }
            }
    }
}

private fun ResolvedModelDto.toChatOption(): ChatModelOption = ChatModelOption(
    modelRef = providerName + "/" + model,
    label = name?.takeIf { it.isNotBlank() } ?: model,
    providerName = providerName,
    reasoning = reasoning,
)

internal fun shouldDeleteUntouchedConversation(
    isNewConversation: Boolean,
    hasMessages: Boolean,
): Boolean = isNewConversation && !hasMessages

/**
 * 一次性提示。
 *
 * 做成类型而不是成品字符串：ViewModel 里没有 `stringResource` / `R`，`ApiError.asText()`
 * 也是 `@Composable` 的，文案必须由 UI 侧解析。
 */
sealed interface ChatNotice {
    /** socket 未连接，帧已进 `pending_ops`，重连后自动补发。 */
    data object Queued : ChatNotice

    /** 文件读不出来（权限、URI 失效等）。 */
    data object AttachFailed : ChatNotice

    data object TurnDeleted : ChatNotice

    /** 上传接口失败。 */
    data class Failure(val error: ApiError) : ChatNotice
}

/**
 * `@专家` 的触发字符（spec §23.5）。
 *
 * 只在两处出现：输入框里的内联文本，以及发送时按它把名字还原成 `agent_id`。
 */
internal const val MENTION_PREFIX = "@"

/** 专家名缺失时退回 `agent_id` —— 候选列表与输入框里的 `@名字` 都靠它。 */
internal fun AgentDto.displayName(): String = name?.takeIf { it.isNotBlank() } ?: agentId

/** 从 `content://` 读出来的待上传内容。读完即用，不落盘。 */
private class AttachmentPayload(val name: String, val mime: String, val bytes: ByteArray)

/**
 * 读取文件选择器给的 URI。
 *
 * 显示名走 `OpenableColumns.DISPLAY_NAME`（`content://` 的 `lastPathSegment` 往往是
 * 一串无意义的 id）；MIME 缺失时用 `application/octet-stream` 兜底，上游按它决定内联方式。
 */
private fun readAttachment(context: Context, uri: Uri): AttachmentPayload? = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: DEFAULT_MIME
    val name = resolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: DEFAULT_FILENAME
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return@runCatching null
    if (bytes.isEmpty()) return@runCatching null
    AttachmentPayload(name = name, mime = mime, bytes = bytes)
}.getOrNull()

private const val DEFAULT_MIME = "application/octet-stream"
private const val DEFAULT_FILENAME = "attachment"
