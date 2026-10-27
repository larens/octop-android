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
import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.TodayCardLifecycle
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.isUnauthorized
import com.octopmobile.app.data.remote.dto.TodaySourceCoverageDto
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayUiState(
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiError? = null,
    val needsLogin: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val container: AppContainer) : ViewModel() {
    private val host = MutableStateFlow<String?>(null)
    private val agentId = MutableStateFlow<String?>(null)

    var uiState by mutableStateOf(TodayUiState())
        private set

    var coverage by mutableStateOf<List<TodaySourceCoverageDto>>(emptyList())
        private set

    val cards: StateFlow<List<TodayCardEntity>> = combine(host, agentId, ::Pair)
        .flatMapLatest { (baseUrl, id) ->
            if (baseUrl.isNullOrBlank() || id.isNullOrBlank()) flowOf(emptyList())
            else container.todayRepository(baseUrl, id).observeCards()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(container.prefs.host, container.prefs.selectedAgentId, ::Pair)
                .collectLatest { (baseUrl, id) ->
                    host.value = baseUrl
                    agentId.value = id
                    coverage = emptyList()
                    uiState = TodayUiState(loaded = baseUrl.isNullOrBlank() || id.isNullOrBlank())
                    if (!baseUrl.isNullOrBlank() && !id.isNullOrBlank()) {
                        if (container.securePrefs.tokenFor(baseUrl) == null) {
                            uiState = uiState.copy(loaded = true, needsLogin = true)
                        } else {
                            refresh()
                        }
                    }
                }
        }
    }

    fun refresh() {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        if (uiState.refreshing) return
        viewModelScope.launch {
            uiState = uiState.copy(refreshing = true)
            val repository = container.todayRepository(baseUrl, id)
            uiState = when (val result = repository.refresh()) {
                is ApiResult.Ok -> {
                    val coverageResult = repository.refreshCoverage()
                    if (coverageResult is ApiResult.Ok) {
                        coverage = coverageResult.value
                    }
                    uiState.copy(
                        loaded = true,
                        refreshing = false,
                        error = null,
                        needsLogin = coverageResult is ApiResult.Failure &&
                            coverageResult.error.isUnauthorized,
                    )
                }
                is ApiResult.Failure -> uiState.copy(
                    loaded = true,
                    refreshing = false,
                    error = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    fun markRead(card: TodayCardEntity) = update(card, TodayCardLifecycle.READ)

    fun dismiss(card: TodayCardEntity) = update(card, TodayCardLifecycle.DISMISSED)

    fun snooze(card: TodayCardEntity) = update(
        card,
        TodayCardLifecycle.SNOOZED,
        System.currentTimeMillis() + SNOOZE_MILLIS,
    )

    fun sendFeedback(card: TodayCardEntity, feedbackType: String) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        viewModelScope.launch {
            when (val result = container.todayRepository(baseUrl, id).sendFeedback(card.id, feedbackType)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Failure -> uiState = uiState.copy(
                    error = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    private fun update(card: TodayCardEntity, lifecycle: String, snoozedUntil: Long? = null) {
        val baseUrl = host.value ?: return
        val id = agentId.value ?: return
        viewModelScope.launch {
            when (val result = container.todayRepository(baseUrl, id).setLifecycle(card.id, lifecycle, snoozedUntil)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Failure -> uiState = uiState.copy(
                    error = result.error,
                    needsLogin = result.error.isUnauthorized,
                )
            }
        }
    }

    companion object {
        private const val SNOOZE_MILLIS = 24L * 60 * 60 * 1000

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TodayViewModel(appContainer()) }
        }
    }
}
