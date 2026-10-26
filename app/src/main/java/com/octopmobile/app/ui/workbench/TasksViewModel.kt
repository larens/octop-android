package com.octopmobile.app.ui.workbench

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.dto.ConnectorInstanceDto
import com.octopmobile.app.data.remote.dto.isReadyForAutomation
import com.octopmobile.app.data.remote.isUnauthorized
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

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(appContainer()) }
        }
    }
}
