package com.octopmobile.app.ui.workbench

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.db.ConversationTaskEntity
import com.octopmobile.app.data.model.ArtifactPreviewKind
import com.octopmobile.app.data.model.ArtifactReference
import com.octopmobile.app.data.model.ChatArtifact
import com.octopmobile.app.data.model.artifactDownloadName
import com.octopmobile.app.data.model.chatArtifacts
import com.octopmobile.app.data.attachments.AttachmentRefs
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.dto.ConnectorInstanceDto
import com.octopmobile.app.data.remote.dto.ArtifactShareBody
import com.octopmobile.app.data.remote.dto.ArtifactShareDto
import com.octopmobile.app.data.remote.dto.TaskApprovalDto
import com.octopmobile.app.data.remote.dto.isReadyForAutomation
import com.octopmobile.app.data.remote.isUnauthorized
import com.octopmobile.app.ui.chat.ArtifactPreview
import com.octopmobile.app.ui.chat.ChatNotice
import com.octopmobile.app.ui.chat.projectTaskApprovals
import com.octopmobile.app.ui.appContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A finished file, enriched with the conversation that produced it. */
data class ArtifactWorkbenchItem(
    val artifact: ChatArtifact,
    val threadId: String,
    val threadTitle: String,
    val updatedAt: Long,
)

data class TasksUiState(
    /** 当前 agent。为 null 表示还没选过 agent —— 那时连 cron 列表都无从拉取。 */
    val agentId: String? = null,
    /** 首次同步是否已经有结论（拿到过服务端的回答，或确定没有可拉的 agent）。 */
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val error: ApiError? = null,
    /** 一次性提示（同步或后台写操作失败）。 */
    val notice: ApiError? = null,
    val naturalTaskCompleted: Boolean = false,
    val naturalTaskError: ApiError? = null,
    val processingNaturalTask: Boolean = false,
    val needsLogin: Boolean = false,
    /** 服务端已安装、已授权且当前用户可用的连接器。 */
    val connectors: List<ConnectorInstanceDto> = emptyList(),
    val connectorsLoaded: Boolean = false,
    /** Conversation/Router tasks across the agent, not just the visible Router thread. */
    val conversationTasks: List<ConversationTaskEntity> = emptyList(),
    val taskApprovals: List<TaskApprovalDto> = emptyList(),
    val approvalBusy: Set<String> = emptySet(),
    val taskActionBusy: Set<String> = emptySet(),
    val mainThreadId: String? = null,
    val artifacts: List<ArtifactWorkbenchItem> = emptyList(),
    val artifactPreview: ArtifactPreview? = null,
    val artifactBusyKey: String? = null,
    val artifactShareBusyKey: String? = null,
    val artifactShare: ArtifactShareDto? = null,
    val artifactNotice: ChatNotice? = null,
)

/**
 * 自动任务（服务端 cron，docs/protocol.md §5.5）。
 *
 * 与 [com.octopmobile.app.ui.threads.ThreadsViewModel] 一样：`agent_id` 从 DataStore 直接读，
 * 列表由 Room 镜像驱动，网络只负责回填。写操作全部走「**服务端先成功、再写本地**」
 * （见 [AutomationRepository]），不做乐观更新。
 *
 * 客户端**不排程**：真正按点触发的是服务端 APScheduler，这里只是这堆 cron 的一面镜子。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(private val container: AppContainer) : ViewModel() {

    private val host = MutableStateFlow<String?>(null)
    private val agentId = MutableStateFlow<String?>(null)

    var uiState by mutableStateOf(TasksUiState())
        private set

    val tasks: StateFlow<List<AutomationTaskEntity>> =
        combine(host, agentId) { baseUrl, id -> baseUrl to id }
            .flatMapLatest { (baseUrl, id) ->
                if (baseUrl.isNullOrBlank() || id.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    container.automationRepository(baseUrl, id).observeTasks()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val baseUrl = container.prefs.currentHost()
            if (baseUrl.isNullOrBlank()) {
                uiState = uiState.copy(loaded = true)
                return@launch
            }
            host.value = baseUrl

            val id = container.prefs.selectedAgentId.first()
            if (id.isNullOrBlank()) {
                uiState = uiState.copy(loaded = true)
                return@launch
            }
            uiState = uiState.copy(agentId = id)

            // 没有令牌时先去登录：cron 接口必然 401，不发这次注定失败的请求
            if (container.securePrefs.tokenFor(baseUrl) == null) {
                uiState = uiState.copy(loaded = true, needsLogin = true)
                return@launch
            }
            agentId.value = id
            sync()
        }
    }

    /** 手动同步（页头的刷新按钮）。 */
    fun sync() {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (uiState.syncing) return
        viewModelScope.launch {
            uiState = uiState.copy(syncing = true)
            uiState = when (val result = container.automationRepository(baseUrl, id).refresh()) {
                is ApiResult.Ok -> uiState.copy(loaded = true, error = null)

                is ApiResult.Failure -> uiState.copy(
                    loaded = true,
                    error = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
            refreshConversationCenter(baseUrl, id)
            refreshArtifacts(baseUrl, id)
            loadConnectors(baseUrl)
            uiState = uiState.copy(syncing = false)
        }
    }

    fun createFromNaturalLanguage(description: String, mcpServers: List<String> = emptyList()) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (uiState.processingNaturalTask || description.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(processingNaturalTask = true, naturalTaskError = null)
            uiState = when (
                val result = container.naturalLanguageTaskRepository(baseUrl, id).create(description, mcpServers)
            ) {
                is ApiResult.Ok -> uiState.copy(
                    processingNaturalTask = false,
                    naturalTaskCompleted = true,
                    loaded = true,
                )
                is ApiResult.Failure -> uiState.copy(
                    processingNaturalTask = false,
                    naturalTaskError = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    fun modifyFromNaturalLanguage(task: AutomationTaskEntity, description: String) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (uiState.processingNaturalTask || description.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(processingNaturalTask = true, naturalTaskError = null)
            uiState = when (
                val result = container.naturalLanguageTaskRepository(baseUrl, id).modify(task, description)
            ) {
                is ApiResult.Ok -> uiState.copy(
                    processingNaturalTask = false,
                    naturalTaskCompleted = true,
                    loaded = true,
                )
                is ApiResult.Failure -> uiState.copy(
                    processingNaturalTask = false,
                    naturalTaskError = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    fun dismissNaturalTask() {
        uiState = uiState.copy(naturalTaskError = null)
    }

    fun approveTaskApproval(approval: TaskApprovalDto) = decideTaskApproval(approval, approve = true)

    fun rejectTaskApproval(approval: TaskApprovalDto) = decideTaskApproval(approval, approve = false)

    fun applyTaskAction(task: ConversationTaskEntity, action: String) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (!task.canActOn() || task.taskId in uiState.taskActionBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(taskActionBusy = uiState.taskActionBusy + task.taskId)
            val repository = container.mainConversationRepository(baseUrl, id)
            when (val result = repository.applyTaskAction(task.taskId, action)) {
                is ApiResult.Failure -> uiState = uiState.copy(
                    notice = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
                is ApiResult.Ok -> when (val tasks = repository.refreshAllTasks()) {
                    is ApiResult.Ok -> uiState = uiState.copy(conversationTasks = tasks.value)
                    is ApiResult.Failure -> if (tasks.error.isUnauthorized) {
                        uiState = uiState.copy(needsLogin = true)
                    }
                }
            }
            uiState = uiState.copy(
                taskActionBusy = uiState.taskActionBusy - task.taskId,
            )
        }
    }

    private fun decideTaskApproval(approval: TaskApprovalDto, approve: Boolean) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (approval.status != "pending" || approval.approvalId in uiState.approvalBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(approvalBusy = uiState.approvalBusy + approval.approvalId)
            val repository = container.mainConversationRepository(baseUrl, id)
            val result = if (approve) {
                repository.approveTaskApproval(approval)
            } else {
                repository.rejectTaskApproval(approval)
            }
            if (result is ApiResult.Failure) {
                uiState = uiState.copy(
                    notice = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
            refreshTaskApprovals(baseUrl, id)
            uiState = uiState.copy(approvalBusy = uiState.approvalBusy - approval.approvalId)
        }
    }

    /** Download through Android's downloader so the file lands in public Downloads. */
    fun downloadArtifact(context: Context, item: ArtifactWorkbenchItem) {
        val host = host.value
        val token = host?.let(container.securePrefs::tokenFor)
        val url = item.artifact.downloadRef?.let { AttachmentRefs.absolute(host.orEmpty(), it) }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (url.isNullOrBlank() || token.isNullOrBlank() || manager == null) {
            uiState = uiState.copy(artifactNotice = ChatNotice.DownloadFailed)
            return
        }
        val filename = artifactDownloadName(item.artifact.filename, System.currentTimeMillis())
        runCatching {
            manager.enqueue(
                DownloadManager.Request(Uri.parse(url)).apply {
                    setTitle(item.artifact.filename)
                    setDescription(item.artifact.formatLabel)
                    setMimeType(item.artifact.mimeType)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    )
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Octop/$filename")
                    addRequestHeader("Authorization", "Bearer $token")
                },
            )
            uiState = uiState.copy(artifactNotice = ChatNotice.DownloadStarted)
        }.onFailure {
            uiState = uiState.copy(artifactNotice = ChatNotice.DownloadFailed)
        }
    }

    /** In-app preview for HTML/Markdown; other formats go to the system viewer. */
    fun previewArtifact(context: Context, item: ArtifactWorkbenchItem) {
        if (uiState.artifactBusyKey != null) return
        uiState = uiState.copy(artifactBusyKey = item.artifact.key, artifactNotice = null)
        viewModelScope.launch {
            try {
                val file = cacheArtifactForOpen(context, item.artifact) ?: return@launch
                if (item.artifact.previewKind != ArtifactPreviewKind.EXTERNAL) {
                    uiState = uiState.copy(artifactPreview = ArtifactPreview(item.artifact, file))
                    return@launch
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, item.artifact.mimeType)
                    clipData = ClipData.newRawUri(item.artifact.filename, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(
                        view,
                        context.getString(com.octopmobile.app.R.string.chat_artifact_preview),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                uiState = uiState.copy(artifactNotice = ChatNotice.PreviewFailed)
            } finally {
                uiState = uiState.copy(artifactBusyKey = null)
            }
        }
    }

    fun closeArtifactPreview() {
        uiState = uiState.copy(artifactPreview = null)
    }

    /** 为已登记在来源会话里的 HTML 产物创建限时公开链接。 */
    fun shareArtifact(item: ArtifactWorkbenchItem) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (!item.artifact.isHtml || uiState.artifactShareBusyKey != null) return
        viewModelScope.launch {
            uiState = uiState.copy(artifactShareBusyKey = item.artifact.key, artifactNotice = null)
            uiState = when (
                val result = container.api(baseUrl).createArtifactShare(
                    agentId = id,
                    threadId = item.threadId,
                    body = ArtifactShareBody(
                        path = item.artifact.path,
                        agentId = item.artifact.agentId,
                    ),
                )
            ) {
                is ApiResult.Ok -> uiState.copy(
                    artifactShareBusyKey = null,
                    artifactShare = result.value,
                )
                is ApiResult.Failure -> uiState.copy(
                    artifactShareBusyKey = null,
                    artifactNotice = ChatNotice.Failure(result.error),
                )
            }
        }
    }

    fun clearArtifactShare() {
        uiState = uiState.copy(artifactShare = null)
    }

    fun consumeArtifactNotice() {
        uiState = uiState.copy(artifactNotice = null)
    }

    fun consumeNotice() {
        uiState = uiState.copy(
            notice = null,
            naturalTaskCompleted = false,
        )
    }

    private suspend fun loadConnectors(baseUrl: String) {
        uiState = when (val result = container.api(baseUrl).connectorInstances()) {
            is ApiResult.Ok -> uiState.copy(
                connectors = result.value.filter(ConnectorInstanceDto::isReadyForAutomation),
                connectorsLoaded = true,
            )
            is ApiResult.Failure -> uiState.copy(
                connectorsLoaded = true,
                needsLogin = uiState.needsLogin || result.error.isUnauthorized,
            )
        }
    }

    private suspend fun refreshConversationCenter(baseUrl: String, id: String) {
        val repository = container.mainConversationRepository(baseUrl, id)
        when (val conversation = repository.refreshConversation()) {
            is ApiResult.Ok -> uiState = uiState.copy(mainThreadId = conversation.value.routerThreadId)
            is ApiResult.Failure -> if (conversation.error.isUnauthorized) {
                uiState = uiState.copy(needsLogin = true)
            }
        }
        when (val tasks = repository.refreshAllTasks()) {
            is ApiResult.Ok -> uiState = uiState.copy(conversationTasks = tasks.value)
            is ApiResult.Failure -> if (tasks.error.isUnauthorized) {
                uiState = uiState.copy(needsLogin = true)
            }
        }
        refreshTaskApprovals(baseUrl, id)
    }

    private suspend fun refreshTaskApprovals(baseUrl: String, id: String) {
        val repository = container.mainConversationRepository(baseUrl, id)
        val conversation = repository.cachedConversation() ?: run {
            uiState = uiState.copy(taskApprovals = emptyList())
            return
        }
        repository.refreshEvents()
        val events = repository.observeEvents(conversation.conversationId).first()
        uiState = uiState.copy(taskApprovals = projectTaskApprovals(events))
    }

    private suspend fun refreshArtifacts(baseUrl: String, id: String) {
        when (val result = container.api(baseUrl).threads(id, limit = 50)) {
            is ApiResult.Ok -> {
                val items = result.value
                    .flatMap { thread ->
                        val references = buildList {
                            thread.artifactRefs.forEach { add(ArtifactReference(it.path, it.agentId)) }
                            thread.artifacts.forEach { add(ArtifactReference(it)) }
                        }
                        chatArtifacts(id, references).map { artifact ->
                            ArtifactWorkbenchItem(
                                artifact = artifact,
                                threadId = thread.threadId,
                                threadTitle = thread.title?.takeIf(String::isNotBlank)
                                    ?: "未命名会话",
                                updatedAt = (thread.lastActive ?: thread.createdAt ?: 0L) * 1000,
                            )
                        }
                    }
                    .distinctBy { it.artifact.key }
                    .sortedByDescending(ArtifactWorkbenchItem::updatedAt)
                uiState = uiState.copy(artifacts = items)
            }
            is ApiResult.Failure -> if (result.error.isUnauthorized) {
                uiState = uiState.copy(needsLogin = true)
            }
        }
    }

    private suspend fun cacheArtifactForOpen(context: Context, artifact: ChatArtifact): File? {
        val host = host.value ?: run {
            uiState = uiState.copy(artifactNotice = ChatNotice.PreviewFailed)
            return null
        }
        val ref = artifact.downloadRef ?: run {
            uiState = uiState.copy(artifactNotice = ChatNotice.PreviewFailed)
            return null
        }
        val url = AttachmentRefs.absolute(host, ref) ?: run {
            uiState = uiState.copy(artifactNotice = ChatNotice.PreviewFailed)
            return null
        }
        return when (val result = withContext(Dispatchers.IO) { container.api(host).download(url) }) {
            is ApiResult.Failure -> {
                uiState = uiState.copy(
                    artifactNotice = ChatNotice.Failure(result.error),
                )
                null
            }
            is ApiResult.Ok -> withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "artifact_previews").apply { mkdirs() }
                File(
                    directory,
                    artifactDownloadName(artifact.filename, System.currentTimeMillis()),
                ).apply { writeBytes(result.value) }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(appContainer()) }
        }
    }
}
