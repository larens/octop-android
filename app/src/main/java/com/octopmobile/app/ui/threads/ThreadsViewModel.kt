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
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.data.remote.isUnauthorized
import com.octopmobile.app.data.repo.InstanceRepository
import com.octopmobile.app.ui.appContainer
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

data class ThreadsUiState(
    val host: String? = null,
    val agents: List<AgentDto> = emptyList(),
    val selectedAgentId: String? = null,
    /** 首次拉 agent 目录期间为 true；只用于「本地一条会话都没有」时的骨架屏。 */
    val agentsLoading: Boolean = true,
    val syncing: Boolean = false,
    val error: ApiError? = null,
    /** 令牌缺失或被服务端拒掉，UI 应回登录页。 */
    val needsLogin: Boolean = false,
    /** 能力探测结论为不兼容，UI 应切 WebView 兜底通道。 */
    val webviewOnly: Boolean = false,
    /** 正在 `POST /threads`。 */
    val creating: Boolean = false,
    /** 新建成功、待导航的会话 id；UI 消费后调 [ThreadsViewModel.consumeCreated]。 */
    val createdThreadId: String? = null,
)

/**
 * 会话列表（docs/spec.md §11 / §12）。
 *
 * 关键约束：**列表由 Room 驱动**。`agent_id` 从 DataStore 直接读，所以冷启动时不需要等
 * `GET /api/agents` 返回就能出会话；网络请求只负责往库里回填。这也是「冷启动秒出列表」
 * 的实现方式 —— 页面里没有全屏 spinner，只有本地为空时的骨架屏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsViewModel(private val container: AppContainer) : ViewModel() {

    private val host = MutableStateFlow<String?>(null)
    private val agentId = MutableStateFlow<String?>(null)

    var uiState by mutableStateOf(ThreadsUiState())
        private set

    val threads: StateFlow<List<ThreadEntity>> =
        combine(host, agentId) { baseUrl, id -> baseUrl to id }
            .flatMapLatest { (baseUrl, id) ->
                if (baseUrl.isNullOrBlank() || id.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    container.threadRepository(baseUrl, id).observeThreads()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val baseUrl = container.prefs.currentHost()
            uiState = uiState.copy(host = baseUrl)
            if (baseUrl.isNullOrBlank()) {
                uiState = uiState.copy(agentsLoading = false)
                return@launch
            }
            host.value = baseUrl

            // `/api/agents` 必然带令牌，没有令牌就先去登录，不发这次注定 401 的请求。
            if (container.securePrefs.tokenFor(baseUrl) == null) {
                uiState = uiState.copy(agentsLoading = false, needsLogin = true)
                return@launch
            }

            // 本地已记住 agent 时先用它把会话列表顶出来，再让网络请求在后面慢慢回填。
            agentId.value = container.prefs.selectedAgentId.first()
            loadAgents(baseUrl)
        }
    }

    fun selectAgent(agent: AgentDto) {
        val baseUrl = host.value ?: return
        if (agent.agentId == uiState.selectedAgentId) return

        uiState = uiState.copy(selectedAgentId = agent.agentId, error = null)
        agentId.value = agent.agentId
        viewModelScope.launch {
            container.prefs.setSelectedAgent(agent.agentId, agent.name)
            refresh(baseUrl, agent.agentId)
        }
    }

    fun refresh() {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        viewModelScope.launch { refresh(baseUrl, id) }
    }

    fun retry() {
        val baseUrl = host.value ?: return
        viewModelScope.launch { loadAgents(baseUrl) }
    }

    /**
     * 新建会话并直接进会话页 —— 与网页版 `/new` 一致：点「+」立刻 `POST /threads`，
     * 不做「先开空页面、发第一条消息才建」。代价是列表会立刻多一条空会话。
     */
    fun createThread() {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (uiState.creating) return

        uiState = uiState.copy(creating = true, error = null)
        viewModelScope.launch {
            when (val result = container.threadRepository(baseUrl, id).create()) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    creating = false,
                    createdThreadId = result.value,
                )

                is ApiResult.Failure -> uiState = uiState.copy(
                    creating = false,
                    error = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    /** 导航已经把新会话推上去了，清掉标记，免得返回列表时又被推一次。 */
    fun consumeCreated() {
        uiState = uiState.copy(createdThreadId = null)
    }

    private suspend fun loadAgents(baseUrl: String) {
        uiState = uiState.copy(agentsLoading = true, error = null)

        when (val result = container.instanceRepository(baseUrl).agents()) {
            is ApiResult.Failure -> uiState = uiState.copy(
                agentsLoading = false,
                error = result.error,
                needsLogin = result.error.isUnauthorized,
            )

            is ApiResult.Ok -> {
                val agents = result.value
                if (agents.isEmpty()) {
                    // 探测项「有 agent」直接失败 —— 原生链路没有可落脚的会话空间（protocol.md §7）。
                    uiState = uiState.copy(agentsLoading = false, webviewOnly = true)
                    container.instanceRepository(baseUrl).markWebviewOnly()
                    return
                }

                val remembered = container.prefs.selectedAgentId.first()
                val selected = agents.firstOrNull { it.agentId == remembered } ?: agents.first()

                uiState = uiState.copy(
                    agents = agents,
                    selectedAgentId = selected.agentId,
                    agentsLoading = false,
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

    private suspend fun refresh(baseUrl: String, id: String) {
        uiState = uiState.copy(syncing = true)
        val result = container.threadRepository(baseUrl, id).refresh()
        uiState = when (result) {
            is ApiResult.Ok -> uiState.copy(syncing = false, error = null)
            is ApiResult.Failure -> uiState.copy(
                syncing = false,
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
