package com.octopmobile.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Owns exactly one dashboard notification socket while the workbench is foregrounded. */
class TodayPushViewModel(private val container: AppContainer) : ViewModel() {
    private var foregroundJob: Job? = null

    fun start() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = viewModelScope.launch {
            combine(container.prefs.host, container.prefs.selectedAgentId, ::Pair)
                .collectLatest { (host, selectedAgentId) ->
                    if (host.isNullOrBlank() || selectedAgentId.isNullOrBlank()) return@collectLatest
                    if (container.securePrefs.tokenFor(host) == null) return@collectLatest
                    val socket = container.dashboardNotificationSocket(host)
                    val collector = launch {
                        socket.todayCards.collect { push ->
                            if (push.agentId == selectedAgentId) {
                                container.todayRepository(host, selectedAgentId).refresh()
                            }
                        }
                    }
                    val threadCollector = launch {
                        socket.threadPushes.collect { push ->
                            if (push.agentId == selectedAgentId) {
                                container.threadRepository(host, selectedAgentId).refresh()
                            }
                        }
                    }
                    socket.connect()
                    try {
                        awaitCancellation()
                    } finally {
                        collector.cancel()
                        threadCollector.cancel()
                        socket.close()
                    }
                }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            val host = container.prefs.currentHost() ?: return@launch
            val agentId = container.prefs.selectedAgentId.first() ?: return@launch
            if (container.securePrefs.tokenFor(host) != null) {
                container.todayRepository(host, agentId).refresh()
            }
        }
    }

    fun stop() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TodayPushViewModel(appContainer()) }
        }
    }
}
