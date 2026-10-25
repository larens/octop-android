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
import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.model.HitlPending
import com.octopmobile.app.data.model.UploadedAttachment
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.dto.HitlDecision
import com.octopmobile.app.data.repo.ChatRepository
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** 附件上传中。 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 一次性提示（离线排队、上传失败等）。文案在 UI 侧解析 —— 这里拿不到 `stringResource`。 */
    private val _notice = MutableStateFlow<ChatNotice?>(null)
    val notice: StateFlow<ChatNotice?> = _notice.asStateFlow()

    init {
        viewModelScope.launch {
            val baseUrl = container.prefs.currentHost()
            if (baseUrl.isNullOrBlank()) {
                uiState = uiState.copy(error = ApiError.Network("no host"))
                return@launch
            }

            val repo = container.chatRepository(baseUrl, agentId, threadId)
            repository = repo

            launch { repo.observeMessages().collect { _messages.value = it } }
            launch { repo.observeThread().collect { _thread.value = it } }
            launch { repo.connection.collect { _connection.value = it } }
            launch { repo.turnActive.collect { _turnActive.value = it } }
            launch { repo.hitl.collect { _hitl.value = it } }
            launch { repo.errors.collect { uiState = uiState.copy(streamError = it) } }

            // 永不返回：连接 + 订阅 + 持续消费事件
            launch { repo.run() }

            loadFirstPage()
        }
    }

    override fun onCleared() {
        // 断开不会取消服务端正在跑的回合（protocol.md §4.2），但本地必须收掉 socket 与重连循环
        repository?.close()
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

    // ------------------------------------------------------------ 发送 / 中止 / 确认

    /**
     * 发送一轮输入。附件在发送时随帧一起走，所以这里先清空待发列表 ——
     * 即使 socket 离线被排进 `pending_ops`，帧里也已经带着它们了。
     */
    fun send(text: String) {
        val repo = repository ?: return
        val files = _attachments.value
        if (text.isBlank() && files.isEmpty()) return

        _attachments.value = emptyList()
        _notice.value = null
        viewModelScope.launch {
            if (!repo.sendTurn(text, files)) _notice.value = ChatNotice.Queued
        }
    }

    fun cancel() {
        val repo = repository ?: return
        viewModelScope.launch { repo.cancel() }
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

    fun clearNotice() {
        _notice.value = null
    }

    private suspend fun loadFirstPage() {
        val repo = repository ?: return
        uiState = uiState.copy(historyLoading = true, error = null)

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

    /** 上传接口失败。 */
    data class Failure(val error: ApiError) : ChatNotice
}

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
