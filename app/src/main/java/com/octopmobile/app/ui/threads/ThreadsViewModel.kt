package com.octopmobile.app.ui.threads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.db.ThreadTagEntity
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.data.remote.isUnauthorized
import com.octopmobile.app.data.repo.InstanceRepository
import com.octopmobile.app.data.repo.ThreadRepository
import com.octopmobile.app.ui.AutoCreatedConversation
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThreadsUiState(
    val host: String? = null,
    val agents: List<AgentDto> = emptyList(),
    val selectedAgentId: String? = null,
    /**
     * 当前 agent 的会话列表是否已经拿到过服务端的答案。本地一条会话都没有时用它区分
     * 「还没问到」（骨架屏）和「确实没有」（空态）—— 只看 `threads.isEmpty()` 的话，
     * agent 目录拉完、会话列表还在路上这段会先闪一下「还没有会话」。
     */
    val threadsLoaded: Boolean = false,
    val syncing: Boolean = false,
    /**
     * 这次同步是用户下拉触发的。下拉用 `PullToRefreshBox` 自带的转圈做反馈，后台同步用
     * 顶部细进度条（spec §12）—— 两个都亮就成了两个忙提示，所以在这里分一下。
     */
    val pulled: Boolean = false,
    val error: ApiError? = null,
    /** 令牌缺失或被服务端拒掉，UI 应回登录页。 */
    val needsLogin: Boolean = false,
    /** 能力探测结论为不兼容，UI 应切 WebView 兜底通道。 */
    val webviewOnly: Boolean = false,
    /** 正在 `POST /threads`。 */
    val creating: Boolean = false,
    /** 新建成功、待导航的会话 id；UI 消费后调 [ThreadsViewModel.consumeCreated]。 */
    val createdThreadId: String? = null,
    /**
     * 改名 / 置顶 / 删除失败的一次性提示。这三个动作都是在列表上就地发生的，
     * 失败了如果只往 [error] 里塞，非空列表根本没人看 —— 所以单独给一个提示位。
     */
    val notice: ApiError? = null,
    /** 稳定主会话入口正在刷新服务端绑定。 */
    val mainConversationLoading: Boolean = false,
    /** 最近一次解析出的稳定入口；保留它以便用户从会话页返回后再次打开。 */
    val mainConversationTarget: MainConversationTarget? = null,
    /** 一次性导航请求；0 表示当前没有待消费的跳转。 */
    val mainConversationNavigationRequest: Long = 0,
    val mainConversationError: ApiError? = null,
)

data class MainConversationTarget(
    val agentId: String,
    val threadId: String,
    val fresh: Boolean,
)

/**
 * 会话列表（docs/spec.md §11 / §12）。
 *
 * 关键约束：**列表由 Room 驱动**。`agent_id` 从 DataStore 直接读，所以冷启动时不需要等
 * `GET /api/agents` 返回就能出会话；网络请求只负责往库里回填。这也是「冷启动秒出列表」
 * 的实现方式 —— 页面里没有全屏 spinner，只有本地为空且还没问到服务端时的骨架屏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsViewModel(private val container: AppContainer) : ViewModel() {

    private val host = MutableStateFlow<String?>(null)
    private val agentId = MutableStateFlow<String?>(null)
    private var handledMainEntryId: Int? = null
    private var resolvingMainEntry = false
    private var pendingMainEntryId: Int? = null
    private var mainNavigationRequestId = 0L

    var uiState by mutableStateOf(ThreadsUiState())
        private set

    val allThreads: StateFlow<List<ThreadEntity>> =
        combine(host, agentId) { baseUrl, id -> baseUrl to id }
            .flatMapLatest { (baseUrl, id) ->
                if (baseUrl.isNullOrBlank() || id.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    container.threadRepository(baseUrl, id).observeThreads()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val archivedThreadIds = MutableStateFlow<Set<String>>(emptySet())

    val threads: StateFlow<List<ThreadEntity>> =
        combine(allThreads, archivedThreadIds) { visible, archived ->
            visible.filterNot { it.threadId in archived }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val archivedThreads: StateFlow<List<ThreadEntity>> =
        combine(allThreads, archivedThreadIds) { visible, archived ->
            visible.filter { it.threadId in archived }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val baseUrl = container.prefs.currentHost()
            uiState = uiState.copy(host = baseUrl)
            if (baseUrl.isNullOrBlank()) {
                uiState = uiState.copy(threadsLoaded = true)
                return@launch
            }
            host.value = baseUrl
            viewModelScope.launch {
                container.prefs.archivedThreadIds.collect { archivedThreadIds.value = it }
            }

            // `/api/agents` 必然带令牌，没有令牌就先去登录，不发这次注定 401 的请求。
            if (container.securePrefs.tokenFor(baseUrl) == null) {
                uiState = uiState.copy(threadsLoaded = true, needsLogin = true)
                return@launch
            }

            // 本地已记住 agent 时先用它把会话列表顶出来，再让网络请求在后面慢慢回填。
            val rememberedAgentId = container.prefs.selectedAgentId.first()
            agentId.value = rememberedAgentId
            uiState = uiState.copy(selectedAgentId = rememberedAgentId)
            loadAgents(baseUrl)
        }
    }

    /**
     * 下拉刷新（spec §11）：走的是增量同步，不是重载页面。
     *
     * 标 [ThreadsUiState.pulled]，让下拉指示器在这个请求期间接管提示，顶部细进度条让位。
     */
    fun refresh() {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        viewModelScope.launch { refresh(baseUrl, id, pulled = true) }
    }

    fun retry() {
        val baseUrl = host.value ?: return
        viewModelScope.launch { loadAgents(baseUrl) }
    }

    /**
     * 新建会话并直接进会话页：只建一条**本地草稿**（`draft-…`），不上服务端。
     *
     * 服务端会话推迟到用户发出第一条消息时才创建（`ChatViewModel.send` 先
     * `createServerThread` 拿 id、再绑定草稿），所以「打开新会话又不说话」不会在服务端
     * 留下任何空会话。草稿会话没互动就随会话页退出被删（[AutoCreatedConversation]）。
     */
    fun createThread() {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (uiState.creating) return

        uiState = uiState.copy(creating = true, error = null)
        viewModelScope.launch {
            val draftId = container.threadRepository(baseUrl, id).createLocalDraft()
            // 记下这是程序给用户开的新会话：没互动就随会话页退出删掉（ChatScreen 消费）。
            AutoCreatedConversation.mark(draftId)
            uiState = uiState.copy(
                creating = false,
                createdThreadId = draftId,
            )
        }
    }

    /** 导航已经把新会话推上去了，清掉标记，免得返回列表时又被推一次。 */
    fun consumeCreated() {
        uiState = uiState.copy(createdThreadId = null)
    }

    /**
     * 普通“会话”Tab 的入口：解析同一 agent 的稳定主会话并打开它绑定的 Thread。
     *
     * [entryId] 由工作台在用户真正选择“会话”时递增。同一个 entry 只处理一次，避免从聊天页
     * 返回时立刻又被弹回去；重新点 Tab 会得到新的 entry，因此仍能主动重新进入主会话。
     */
    fun openMainConversation(entryId: Int) {
        if (handledMainEntryId == entryId) return
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (resolvingMainEntry) {
            pendingMainEntryId = entryId
            return
        }

        handledMainEntryId = entryId
        resolvingMainEntry = true
        uiState = uiState.copy(
            mainConversationLoading = true,
            mainConversationError = null,
        )
        viewModelScope.launch {
            try {
                val repository = container.mainConversationRepository(baseUrl, id)
                when (
                    val resolution = resolveMainConversationEntry(
                        refreshConversation = repository::refreshConversation,
                        createDraft = {
                            container.threadRepository(baseUrl, id).createLocalDraft().also {
                                AutoCreatedConversation.mark(it)
                            }
                        },
                    )
                ) {
                    is MainConversationResolution.Failed -> {
                        val cachedThreadId = repository.cachedConversation()
                            ?.routerThreadId
                            ?.trim()
                            .orEmpty()
                        if (cachedThreadId.isNotEmpty()) {
                            requestMainConversationNavigation(
                                MainConversationTarget(
                                    agentId = id,
                                    threadId = cachedThreadId,
                                    fresh = false,
                                ),
                            )
                            viewModelScope.launch {
                                repository.refreshTasks()
                                repository.refreshEvents()
                            }
                        } else {
                            uiState = uiState.copy(mainConversationError = resolution.error)
                        }
                    }

                    is MainConversationResolution.Ready -> {
                        requestMainConversationNavigation(
                            MainConversationTarget(
                                agentId = id,
                                threadId = resolution.threadId,
                                fresh = resolution.fresh,
                            ),
                        )
                        if (!resolution.fresh) {
                            // The stable Thread can open immediately; governance/task projections
                            // continue in the background from the persisted event cursor.
                            viewModelScope.launch {
                                repository.refreshTasks()
                                repository.refreshEvents()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                uiState = uiState.copy(
                    mainConversationError = ApiError.Parse(
                        e.message ?: "failed to open the main conversation",
                    ),
                )
            } finally {
                resolvingMainEntry = false
                uiState = uiState.copy(mainConversationLoading = false)
                pendingMainEntryId?.let { nextEntryId ->
                    pendingMainEntryId = null
                    if (nextEntryId != handledMainEntryId) openMainConversation(nextEntryId)
                }
            }
        }
    }

    /** 显式 NEW_THREAD / 分享入口由会话页消费，不再重复解析主会话。 */
    fun markMainConversationEntryHandled(entryId: Int) {
        handledMainEntryId = entryId
        cancelMainConversationNavigation()
    }

    fun retryMainConversation(entryId: Int) {
        handledMainEntryId = null
        openMainConversation(entryId)
    }

    /** 从聊天页返回后，用户仍可通过当前入口按钮再次进入最近解析出的主会话。 */
    fun reopenMainConversation() {
        uiState.mainConversationTarget?.let(::requestMainConversationNavigation)
    }

    fun consumeMainConversationNavigation() {
        uiState = uiState.copy(mainConversationNavigationRequest = 0)
    }

    // ------------------------------------------------------------ 长按菜单（spec §11）

    /**
     * 置顶 / 取消置顶、改名、删除。三者同构：**服务端成功才动本地**（见
     * [ThreadRepository.setPinned]），写库即刷新界面。
     *
     * 不做乐观更新：置顶失败却已经跳到了「置顶」分组，用户会以为改成功了。
     */
    fun togglePin(thread: ThreadEntity) = mutate { it.setPinned(thread.threadId, !thread.pinned) }

    fun rename(threadId: String, title: String) = mutate { it.rename(threadId, title) }

    /**
     * 删会话时顺带清掉它的标签 —— 标签是纯本地的（[com.octopmobile.app.data.repo.TagRepository]），
     * 会话没了以后那些行永远指不回去，留着就是孤儿。
     */
    fun delete(threadId: String) = mutate(
        onSuccess = { container.tagRepository().deleteForThread(threadId) },
    ) { it.delete(threadId) }

    fun archive(threadId: String) {
        viewModelScope.launch {
            container.prefs.setThreadArchived(threadId, archived = true)
        }
    }

    fun unarchive(threadId: String) {
        viewModelScope.launch {
            container.prefs.setThreadArchived(threadId, archived = false)
        }
    }

    fun consumeNotice() {
        uiState = uiState.copy(notice = null)
    }

    private fun requestMainConversationNavigation(target: MainConversationTarget) {
        mainNavigationRequestId += 1
        uiState = uiState.copy(
            mainConversationTarget = target,
            mainConversationNavigationRequest = mainNavigationRequestId,
            mainConversationError = null,
        )
    }

    private fun cancelMainConversationNavigation() {
        uiState = uiState.copy(mainConversationNavigationRequest = 0)
    }

    private fun mutate(
        onSuccess: suspend () -> Unit = {},
        action: suspend (ThreadRepository) -> ApiResult<Unit>,
    ) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        viewModelScope.launch {
            when (val result = action(container.threadRepository(baseUrl, id))) {
                is ApiResult.Ok -> onSuccess()
                is ApiResult.Failure -> uiState = uiState.copy(
                    notice = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    // ------------------------------------------------------------ 标签（spec §23.3）

    /**
     * 某个会话的标签。**不经过网络**：上游没有 tag 接口，标签只存在本机库里，
     * 写库即生效、Flow 立刻推给对话框。
     */
    fun tagsForThread(threadId: String): Flow<List<ThreadTagEntity>> =
        container.tagRepository().observeForThread(threadId)

    fun addTag(threadId: String, tag: String, kind: String) {
        viewModelScope.launch { container.tagRepository().add(threadId, tag, kind) }
    }

    fun removeTag(threadId: String, tag: String) {
        viewModelScope.launch { container.tagRepository().remove(threadId, tag) }
    }

    private suspend fun loadAgents(baseUrl: String) {
        uiState = uiState.copy(threadsLoaded = false, error = null)

        when (val result = container.instanceRepository(baseUrl).agents()) {
            is ApiResult.Failure -> uiState = uiState.copy(
                threadsLoaded = true,
                error = result.error,
                needsLogin = result.error.isUnauthorized,
            )

            is ApiResult.Ok -> {
                val agents = result.value
                if (agents.isEmpty()) {
                    // 探测项「有 agent」直接失败 —— 原生链路没有可落脚的会话空间（protocol.md §7）。
                    uiState = uiState.copy(threadsLoaded = true, webviewOnly = true)
                    container.instanceRepository(baseUrl).markWebviewOnly()
                    return
                }

                val remembered = container.prefs.selectedAgentId.first()
                val selected = agents.firstOrNull { it.agentId == remembered } ?: agents.first()

                uiState = uiState.copy(
                    agents = agents,
                    selectedAgentId = selected.agentId,
                    error = null,
                )
                agentId.value = selected.agentId
                container.prefs.setSelectedAgent(selected.agentId, selected.name)

                refresh(baseUrl, selected.agentId)
                // WS 探测最长 16s，不能挡住列表首屏。
                viewModelScope.launch { probeCapability(baseUrl, selected.agentId) }
            }
        }
    }

    /** [pulled] 表示这次同步由用户下拉触发，UI 据此决定亮下拉指示器还是顶部细进度条。 */
    private suspend fun refresh(baseUrl: String, id: String, pulled: Boolean = false) {
        uiState = uiState.copy(syncing = true, pulled = pulled)
        val result = container.threadRepository(baseUrl, id).refresh()
        uiState = when (result) {
            is ApiResult.Ok -> uiState.copy(
                syncing = false,
                pulled = false,
                threadsLoaded = true,
                error = null,
            )

            is ApiResult.Failure -> uiState.copy(
                syncing = false,
                pulled = false,
                threadsLoaded = true,
                error = result.error,
                needsLogin = result.error.isUnauthorized,
            )
        }
    }

    /**
     * 能力探测（docs/spec.md §4）。只有已判定为 `native` 的实例才跳过探测 —— WS 探测最长 16s，
     * 每次冷启动都跑的代价太大。
     *
     * `webview_only` 是**不能永久缓存的**：客户端或服务端任意一侧修好之后，用户必须能自己回到
     * 原生通道。一次误判（历史版本里探测路径拼错就误判过）不该把用户永久锁在网页版里，而且
     * 重新探测的代价很小 —— REST 那一步失败时立刻就有结论，不会走到昂贵的 WS 探测。
     */
    private suspend fun probeCapability(baseUrl: String, id: String) {
        val repository = container.instanceRepository(baseUrl)
        if (repository.recordedCapability() == InstanceRepository.Capability.NATIVE) return

        if (repository.probe(id) == InstanceRepository.Capability.WEBVIEW_ONLY) {
            uiState = uiState.copy(webviewOnly = true)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ThreadsViewModel(appContainer()) }
        }
    }
}
