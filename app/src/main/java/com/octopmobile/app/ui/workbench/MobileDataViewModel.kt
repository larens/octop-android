package com.octopmobile.app.ui.workbench

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.db.MobileEventEntity
import com.octopmobile.app.data.db.MobileSourceEntity
import com.octopmobile.app.data.db.MobileSourceType
import com.octopmobile.app.data.mobile.AndroidAppConnectorAccess
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

    fun onRuntimePermissionResult(
        context: Context,
        sourceType: String,
        results: Map<String, Boolean>,
    ) {
        viewModelScope.launch {
            val granted = AndroidAppConnectorAccess.runtimeAccessGranted(
                context,
                sourceType,
                results,
            )
            if (granted) {
                enableAndCollect(context, sourceType)
            } else {
                repository.setSourceState(sourceType, false, "denied", "权限未授予")
                transient.value = transient.value.copy(notice = "权限未授予，未开启该应用")
            }
        }
    }

    fun onSpecialAccessReturned(context: Context, sourceType: String) {
        viewModelScope.launch {
            if (AndroidAppConnectorAccess.hasAccess(context, sourceType)) {
                enableAndCollect(context, sourceType)
            } else {
                repository.setSourceState(
                    sourceType,
                    false,
                    "denied",
                    "需要在系统设置中开启访问权限",
                )
                transient.value = transient.value.copy(notice = "尚未在系统设置中授予访问权限")
            }
        }
    }

    fun refreshSource(context: Context, sourceType: String) {
        viewModelScope.launch {
            if (!AndroidAppConnectorAccess.hasAccess(context, sourceType)) {
                repository.setSourceState(sourceType, false, "denied", "权限已失效")
                transient.value = transient.value.copy(notice = "权限已失效，请重新连接")
                return@launch
            }
            if (sourceType == MobileSourceType.ANDROID_NOTIFICATIONS) {
                transient.value = transient.value.copy(
                    notice = "通知会在收到时自动进入本机加密队列",
                )
                return@launch
            }
            collectEnabledSource(context, sourceType)
        }
    }

    private suspend fun enableAndCollect(context: Context, sourceType: String) {
        repository.setSourceState(sourceType, true, "granted")
        if (sourceType == MobileSourceType.ANDROID_NOTIFICATIONS) {
            transient.value = transient.value.copy(
                notice = "已连接通知；新通知会进入本机加密队列",
            )
            return
        }
        collectEnabledSource(context, sourceType)
    }

    private suspend fun collectEnabledSource(context: Context, sourceType: String) {
        transient.value = transient.value.copy(busy = true, notice = "正在读取 ${sourceLabel(sourceType)}")
        runCatching {
            repository.collect(sourceType) {
                AndroidAppConnectorAccess.collect(context, sourceType)
            }
        }.onSuccess { count ->
            transient.value = transient.value.copy(
                busy = false,
                notice = if (count == 0) {
                    "来源未提供数据"
                } else {
                    "已采集 $count 条记录"
                },
            )
        }.onFailure {
            transient.value = transient.value.copy(
                busy = false,
                notice = "读取失败，请检查系统权限和来源设置",
            )
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

    private fun sourceLabel(sourceType: String): String = when (sourceType) {
        MobileSourceType.SYSTEM_CALENDAR -> "系统日历"
        MobileSourceType.ANDROID_ALARM_TIMER -> "闹钟和计时器"
        MobileSourceType.ANDROID_USAGE_STATS -> "使用时间"
        MobileSourceType.ANDROID_CONTACTS -> "通讯录"
        MobileSourceType.ANDROID_FILES -> "文件"
        MobileSourceType.ANDROID_LOCATION -> "位置"
        MobileSourceType.ANDROID_SMS -> "短信"
        else -> "应用数据"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MobileDataViewModel(appContainer()) }
        }
    }
}
