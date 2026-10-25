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
import com.octopmobile.app.data.remote.isUnauthorized
import com.octopmobile.app.data.repo.AutomationRepository
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
    /** 一次性提示（写操作失败 / 已触发执行）。 */
    val notice: ApiError? = null,
    /** 刚在服务端触发了一次「立即执行」，UI 该弹一次提示。 */
    val runTriggered: Boolean = false,
    /** 正在等这次写操作返回的任务 id；非 null 时所有写按钮都应禁用。 */
    val busyId: String? = null,
    val needsLogin: Boolean = false,
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
                is ApiResult.Ok -> uiState.copy(syncing = false, loaded = true, error = null)

                is ApiResult.Failure -> uiState.copy(
                    syncing = false,
                    loaded = true,
                    error = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    // ------------------------------------------------------------ 增删改

    fun create(title: String, prompt: String, scheduleRule: String) =
        write { it.create(title, prompt, scheduleRule) }

    fun update(id: String, title: String, prompt: String, scheduleRule: String) =
        write(id) { it.update(id, title, prompt, scheduleRule) }

    fun setEnabled(id: String, enabled: Boolean) = write(id) { it.setEnabled(id, enabled) }

    fun delete(id: String) = write(id) { it.delete(id) }

    /** 立即执行一次。与其它写操作一样等 204 回来才提示，但**不改本地排期**。 */
    fun runNow(id: String) {
        val baseUrl = host.value ?: return
        val id2 = agentId.value ?: return
        if (uiState.busyId != null) return
        viewModelScope.launch {
            uiState = uiState.copy(busyId = id)
            uiState = when (val result = container.automationRepository(baseUrl, id2).runNow(id)) {
                is ApiResult.Ok -> uiState.copy(busyId = null, runTriggered = true)

                is ApiResult.Failure -> uiState.copy(
                    busyId = null,
                    notice = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    fun consumeNotice() {
        uiState = uiState.copy(notice = null, runTriggered = false)
    }

    /**
     * 写操作的统一出口：先是服务端，成功了仓储自己会把返回的对象写进镜像。
     *
     * [busyId] 传具体任务 id 时，UI 可以只对这一行做忙态；不传则是「新建」这种没有行的操作。
     */
    private fun write(
        busyId: String? = null,
        action: suspend (AutomationRepository) -> ApiResult<Unit>,
    ) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (uiState.busyId != null) return
        viewModelScope.launch {
            uiState = uiState.copy(busyId = busyId)
            uiState = when (val result = action(container.automationRepository(baseUrl, id))) {
                is ApiResult.Ok -> uiState.copy(busyId = null)

                is ApiResult.Failure -> uiState.copy(
                    busyId = null,
                    notice = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(appContainer()) }
        }
    }
}
