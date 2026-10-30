package com.octopmobile.app.ui.workbench

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.db.MobileEventEntity
import com.octopmobile.app.data.db.MobileSourceEntity
import com.octopmobile.app.data.db.MobileSourceType
import com.octopmobile.app.data.mobile.CalendarSourceAdapter
import com.octopmobile.app.data.mobile.ShareInboxSourceAdapter
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.ui.Handoff
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MobileDataUiState(
    val sources: List<MobileSourceEntity> = emptyList(),
    val events: List<MobileEventEntity> = emptyList(),
    val pendingCount: Int = 0,
    val busy: Boolean = false,
    val notice: String? = null,
)

class MobileDataViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.mobileDataRepository()
    private val transient = kotlinx.coroutines.flow.MutableStateFlow(MobileDataUiState())

    val uiState: StateFlow<MobileDataUiState> = combine(
        repository.observeSources().map { sources ->
            sources.filterNot {
                it.sourceType == MobileSourceType.HEALTH_CONNECT ||
                    it.sourceType == MobileSourceType.XIAMI_HEALTH
            }
        },
        repository.observeRecent().map { events ->
            events.filterNot {
                it.sourceType == MobileSourceType.HEALTH_CONNECT ||
                    it.sourceType == MobileSourceType.XIAMI_HEALTH
            }
        },
        repository.observePendingCount(),
        transient,
    ) { sources, events, pending, local ->
        local.copy(sources = sources, events = events, pendingCount = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MobileDataUiState())

    init {
        viewModelScope.launch { repository.ensureSources() }
    }

    fun saveShare(context: Context, handoff: Handoff) {
        viewModelScope.launch {
            repository.saveShare(ShareInboxSourceAdapter(context.contentResolver).collect(handoff.text, handoff.uris))
            transient.value = transient.value.copy(notice = "已存入手机收集箱，等待服务端接收")
        }
    }

    fun prepareAgentPrompt(event: MobileEventEntity, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.agentPrompt(event) }
                .onSuccess(onReady)
                .onFailure { transient.value = transient.value.copy(notice = "无法读取这条本机线索") }
        }
    }

    fun setDisabled(sourceType: String) {
        viewModelScope.launch { repository.disableSource(sourceType) }
    }

    fun onCalendarPermission(context: Context, granted: Boolean) {
        viewModelScope.launch {
            repository.setSourceState(
                MobileSourceType.SYSTEM_CALENDAR,
                granted,
                if (granted) "granted" else "denied",
            )
            if (granted) collectCalendar(context)
        }
    }

    fun collectCalendar(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            transient.value = transient.value.copy(notice = "需要日历读取权限")
            return
        }
        runCollection("正在读取系统日历") {
            repository.collect(MobileSourceType.SYSTEM_CALENDAR) {
                CalendarSourceAdapter(context.contentResolver).readWindow()
            }
        }
    }

    fun upload() {
        viewModelScope.launch {
            transient.value = transient.value.copy(busy = true, notice = null)
            val host = container.prefs.currentHost()
            val agentId = container.prefs.selectedAgentId.first()
            if (host.isNullOrBlank() || agentId.isNullOrBlank()) {
                transient.value = transient.value.copy(busy = false, notice = "请先连接实例并选择 Agent")
                return@launch
            }
            when (val result = repository.uploadPending(container.api(host), agentId, container.mobileDeviceId())) {
                is ApiResult.Ok -> transient.value = transient.value.copy(
                    busy = false,
                    notice = if (result.value > 0) "服务端已接收 ${result.value} 条" else "没有待同步数据",
                )
                is ApiResult.Failure -> transient.value = transient.value.copy(
                    busy = false,
                    notice = "服务端尚未接收，数据安全保留在本机",
                )
            }
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch { repository.deleteEvent(id) }
    }

    fun consumeNotice() {
        transient.value = transient.value.copy(notice = null)
    }

    private fun runCollection(label: String, block: suspend () -> Int) {
        viewModelScope.launch {
            transient.value = transient.value.copy(busy = true, notice = label)
            runCatching { block() }
                .onSuccess { count ->
                    transient.value = transient.value.copy(
                        busy = false,
                        notice = if (count == 0) "来源未提供数据" else "已采集 $count 条记录",
                    )
                }
                .onFailure { transient.value = transient.value.copy(busy = false, notice = "读取失败，请检查权限和数据来源") }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MobileDataViewModel(appContainer()) }
        }
    }
}
