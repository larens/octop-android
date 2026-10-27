package com.octopmobile.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.dto.InterestMonitorDto
import com.octopmobile.app.data.remote.dto.TodayGoalDto
import com.octopmobile.app.data.remote.isUnauthorized
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class InterestMonitorUiState(
    val monitors: List<InterestMonitorDto> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: ApiError? = null,
    val notice: String? = null,
    val needsLogin: Boolean = false,
    val goals: List<TodayGoalDto> = emptyList(),
)

class InterestMonitorViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(InterestMonitorUiState())
    val uiState: StateFlow<InterestMonitorUiState> = mutableState.asStateFlow()
    private var host: String? = null
    private var agentId: String? = null

    init {
        viewModelScope.launch {
            host = container.prefs.currentHost()
            agentId = container.prefs.selectedAgentId.first()
            if (host.isNullOrBlank() || agentId.isNullOrBlank()) {
                mutableState.value = mutableState.value.copy(loaded = true)
            } else if (container.securePrefs.tokenFor(host!!) == null) {
                mutableState.value = mutableState.value.copy(loaded = true, needsLogin = true)
            } else {
                refreshAll()
            }
        }
    }

    private suspend fun refreshAll() {
        val baseUrl = host ?: return
        val id = agentId ?: return
        val goals = container.todayGoalRepository(baseUrl, id).list()
        if (goals is ApiResult.Ok) mutableState.value = mutableState.value.copy(goals = goals.value)
        refresh()
    }

    fun refresh() = runAction { repository ->
        when (val result = repository.list()) {
            is ApiResult.Ok -> mutableState.value = mutableState.value.copy(
                monitors = result.value,
                loaded = true,
                busy = false,
                error = null,
            )
            is ApiResult.Failure -> fail(result.error)
        }
    }

    fun create(description: String, schedule: String, goalId: String?) = runAction { repository ->
        if (description.trim().length < 3) {
            mutableState.value = mutableState.value.copy(busy = false, notice = "请描述你想持续关注的内容")
            return@runAction
        }
        when (val result = repository.create(description, schedule, goalId)) {
            is ApiResult.Ok -> {
                mutableState.value = mutableState.value.copy(
                    monitors = listOf(result.value) + mutableState.value.monitors,
                    busy = false,
                    notice = "监控已创建；首次检查只建立基线，不会推送旧结果",
                )
            }
            is ApiResult.Failure -> fail(result.error)
        }
    }

    fun createGoal(title: String, definition: String, successRules: String, reviewDays: Int) {
        val baseUrl = host ?: return
        val id = agentId ?: return
        if (title.trim().length < 2 || definition.trim().length < 3 || successRules.trim().length < 3) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null, notice = null)
            val reviewAt = System.currentTimeMillis() / 1000 + reviewDays * 24L * 60 * 60
            when (val result = container.todayGoalRepository(baseUrl, id).create(title, definition, successRules, reviewAt)) {
                is ApiResult.Ok -> mutableState.value = mutableState.value.copy(
                    goals = listOf(result.value) + mutableState.value.goals,
                    busy = false,
                    notice = "目标已创建，可在新监控中关联",
                )
                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    fun setGoalStatus(goal: TodayGoalDto, status: String) {
        val baseUrl = host ?: return
        val id = agentId ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            when (val result = container.todayGoalRepository(baseUrl, id).update(goal.goalId, status = status)) {
                is ApiResult.Ok -> mutableState.value = mutableState.value.copy(
                    goals = mutableState.value.goals.map { if (it.goalId == goal.goalId) result.value else it },
                    busy = false,
                )
                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    fun reconfirmGoal(goal: TodayGoalDto) {
        val baseUrl = host ?: return
        val id = agentId ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            val reviewAt = System.currentTimeMillis() / 1000 + 30L * 24 * 60 * 60
            when (val result = container.todayGoalRepository(baseUrl, id).update(
                goal.goalId,
                confirm = true,
                reviewAt = reviewAt,
            )) {
                is ApiResult.Ok -> mutableState.value = mutableState.value.copy(
                    goals = mutableState.value.goals.map { if (it.goalId == goal.goalId) result.value else it },
                    busy = false,
                    notice = "目标已确认，30 天后再次复核",
                )
                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    fun setEnabled(item: InterestMonitorDto, enabled: Boolean) = runAction { repository ->
        when (val result = repository.setEnabled(item.monitorId, enabled)) {
            is ApiResult.Ok -> replace(result.value)
            is ApiResult.Failure -> fail(result.error)
        }
    }

    fun run(item: InterestMonitorDto, onCardsMayChange: () -> Unit) = runAction { repository ->
        when (val result = repository.run(item.monitorId)) {
            is ApiResult.Ok -> {
                replace(result.value.monitor)
                mutableState.value = mutableState.value.copy(
                    notice = when {
                        !item.baselineEstablished -> "已建立搜索基线"
                        result.value.publishedCount > 0 -> "发现 ${result.value.publishedCount} 条新变化，已加入 Today"
                        else -> "本次没有发现新的变化"
                    },
                )
                if (result.value.publishedCount > 0) {
                    val baseUrl = host
                    val id = agentId
                    if (baseUrl != null && id != null && container.todayRepository(baseUrl, id).refresh() is ApiResult.Ok) {
                        onCardsMayChange()
                    }
                }
            }
            is ApiResult.Failure -> fail(result.error)
        }
    }

    fun delete(item: InterestMonitorDto) = runAction { repository ->
        when (val result = repository.delete(item.monitorId)) {
            is ApiResult.Ok -> mutableState.value = mutableState.value.copy(
                monitors = mutableState.value.monitors.filterNot { it.monitorId == item.monitorId },
                busy = false,
            )
            is ApiResult.Failure -> fail(result.error)
        }
    }

    fun consumeNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    private fun runAction(block: suspend (com.octopmobile.app.data.repo.InterestMonitorRepository) -> Unit) {
        val baseUrl = host ?: return
        val id = agentId ?: return
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null, notice = null)
            block(container.interestMonitorRepository(baseUrl, id))
        }
    }

    private fun replace(item: InterestMonitorDto) {
        mutableState.value = mutableState.value.copy(
            monitors = mutableState.value.monitors.map { if (it.monitorId == item.monitorId) item else it },
            busy = false,
        )
    }

    private fun fail(error: ApiError) {
        mutableState.value = mutableState.value.copy(
            loaded = true,
            busy = false,
            error = error,
            needsLogin = error.isUnauthorized,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { InterestMonitorViewModel(appContainer()) }
        }
    }
}
