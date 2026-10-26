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
import com.octopmobile.app.data.db.MemoryItemEntity
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.isUnauthorized
import com.octopmobile.app.data.repo.MemoryRepository
import com.octopmobile.app.data.repo.ServerMemorySnapshot
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MemoryUiState(
    val host: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    val snapshot: ServerMemorySnapshot? = null,
    val refreshing: Boolean = false,
    val error: ApiError? = null,
    val needsLogin: Boolean = false,
)

/** 记忆页：服务端画像 / 情绪 / 沉淀 + 本地手工补充。 */
class MemoryViewModel(private val container: AppContainer) : ViewModel() {

    var uiState by mutableStateOf(MemoryUiState())
        private set

    val items: StateFlow<List<MemoryItemEntity>> = container.memoryRepository().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(
                container.prefs.host,
                container.prefs.selectedAgentId,
                container.prefs.selectedAgentName,
            ) { host, agentId, agentName ->
                Triple(host, agentId, agentName)
            }.collectLatest { (host, agentId, agentName) ->
                uiState = uiState.copy(
                    host = host,
                    agentId = agentId,
                    agentName = agentName,
                    snapshot = null,
                    error = null,
                    needsLogin = false,
                )
                if (host.isNullOrBlank() || agentId.isNullOrBlank()) {
                    uiState = uiState.copy(snapshot = null, refreshing = false)
                } else {
                    refresh(host, agentId, agentName)
                }
            }
        }
    }

    /**
     * 新建 / 修改一条记忆，三种情形合成一个入口：
     *
     * - [id] 非空 → 改已有那条（[sourceThreadId] 沿用原值，改的是内容不是来源）；
     * - [id] 为空、[sourceThreadId] 非空 → 从会话里存下来，带上来源；
     * - 都为空 → 记忆页里手写的新条目。
     */
    fun save(
        id: String?,
        kind: String,
        title: String,
        summary: String,
        sourceThreadId: String? = null,
    ) {
        viewModelScope.launch {
            val repository: MemoryRepository = container.memoryRepository()
            when {
                id != null -> repository.update(id, kind, title, summary)
                sourceThreadId != null -> repository.create(kind, title, summary, sourceThreadId = sourceThreadId)
                else -> repository.create(kind, title, summary)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.memoryRepository().delete(id) }
    }

    fun refresh() {
        val host = uiState.host ?: return
        val agentId = uiState.agentId ?: return
        viewModelScope.launch { refresh(host, agentId, uiState.agentName) }
    }

    private suspend fun refresh(host: String, agentId: String, agentName: String?) {
        uiState = uiState.copy(
            host = host,
            agentId = agentId,
            agentName = agentName,
            refreshing = true,
            error = null,
            needsLogin = false,
        )
        when (val result = container.serverMemoryRepository(host).snapshot(agentId)) {
            is ApiResult.Ok -> uiState = uiState.copy(
                refreshing = false,
                snapshot = result.value,
                error = null,
                needsLogin = false,
            )

            is ApiResult.Failure -> uiState = uiState.copy(
                refreshing = false,
                error = result.error,
                needsLogin = result.error.isUnauthorized,
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MemoryViewModel(appContainer()) }
        }
    }
}
