package com.octopmobile.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.dto.ApiErrorCode

/**
 * ViewModel 工厂里拿容器。ViewModel 不持有 Activity，只依赖 [AppContainer]，
 * 所以从 `APPLICATION_KEY` 取全局实例即可。
 */
fun CreationExtras.appContainer(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctopApp).container

/**
 * 把 [ApiError] 翻成给用户看的一句话。
 *
 * 上游的 `code` 比 HTTP 状态码精确得多 —— 同样是 401，`AUTH_FAILED` 是「密码错了」，
 * `FORBIDDEN` 是「登录失效了」，提示语完全不同（docs/protocol.md §2）。
 */
@Composable
fun ApiError.asText(): String = when (this) {
    is ApiError.Http -> when (code) {
        ApiErrorCode.AUTH_FAILED -> stringResource(R.string.err_auth_failed)
        ApiErrorCode.LOGIN_LOCKED -> stringResource(R.string.err_login_locked, retryAfterSeconds ?: 0L)
        ApiErrorCode.SETUP_REQUIRED -> stringResource(R.string.err_setup_required)
        ApiErrorCode.FORBIDDEN -> stringResource(R.string.err_session_expired)
        else -> message?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.err_http_status, status)
    }

    is ApiError.Network -> stringResource(R.string.err_network)
    is ApiError.Parse -> stringResource(R.string.err_unknown, message)
}
