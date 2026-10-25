package com.octopmobile.app.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.launch

data class LoginUiState(
    val host: String? = null,
    val username: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: ApiError? = null,
    /** 登录成功：令牌已进加密存储，可以进会话列表了。 */
    val done: Boolean = false,
)

/**
 * 登录（docs/spec.md §9.5 探测第 2 项）。
 *
 * 只负责拿到令牌；agent 列表与能力探测放到会话列表页 ——
 * 那两件事不该挡住「登录成功」这个反馈。
 */
class LoginViewModel(private val container: AppContainer) : ViewModel() {

    var uiState by mutableStateOf(LoginUiState())
        private set

    init {
        viewModelScope.launch {
            uiState = uiState.copy(host = container.prefs.currentHost())
        }
    }

    fun onUsernameChange(value: String) {
        uiState = uiState.copy(username = value, error = null)
    }

    fun onPasswordChange(value: String) {
        uiState = uiState.copy(password = value, error = null)
    }

    fun submit() {
        val state = uiState
        if (state.submitting) return
        if (state.username.isBlank() || state.password.isBlank()) return

        uiState = state.copy(submitting = true, error = null)
        viewModelScope.launch {
            val host = container.prefs.currentHost()
            if (host.isNullOrBlank()) {
                uiState = uiState.copy(submitting = false, error = ApiError.Network("no host"))
                return@launch
            }

            val result = container.instanceRepository(host).login(state.username.trim(), state.password)
            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(submitting = false, password = "", done = true)
                is ApiResult.Failure -> uiState.copy(submitting = false, error = result.error)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(appContainer()) }
        }
    }
}
