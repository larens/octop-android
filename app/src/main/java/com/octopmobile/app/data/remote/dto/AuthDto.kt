package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// 依据 docs/protocol.md §2 / §5。字段名与上游一致，不做重命名 —— 改动只会让 grep 对不上源码。

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("captcha_token") val captchaToken: String? = null,
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: UserDto? = null,
)

@Serializable
data class UserDto(
    val id: Long? = null,
    val username: String? = null,
    val role: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val locale: String? = null,
    /**
     * `effective_permissions()` 的返回结构未在上游文档化（可能是字符串数组，也可能是按域分组的对象），
     * 这里保留原始 JSON，避免猜错类型导致整个登录响应解析失败。
     */
    val permissions: JsonElement? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * 错误响应体：`{"error": {"code": "AUTH_FAILED", "message": "...", "details": {}}}`。
 *
 * 已知 code：`AUTH_FAILED` / `LOGIN_LOCKED`（`details.retry_after_seconds`）/ `SETUP_REQUIRED` /
 * `FORBIDDEN` / `AGENT_NOT_FOUND` / `THREAD_TURN_ACTIVE` / `INTERNAL_ERROR`。
 */
@Serializable
data class ApiErrorEnvelope(
    val error: ApiErrorBody? = null,
)

@Serializable
data class ApiErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: JsonObject? = null,
)

object ApiErrorCode {
    const val AUTH_FAILED = "AUTH_FAILED"
    const val LOGIN_LOCKED = "LOGIN_LOCKED"
    const val SETUP_REQUIRED = "SETUP_REQUIRED"
    const val FORBIDDEN = "FORBIDDEN"
    const val AGENT_NOT_FOUND = "AGENT_NOT_FOUND"
    const val THREAD_TURN_ACTIVE = "THREAD_TURN_ACTIVE"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

/** `GET /api/health` 的响应。**没有版本号字段**，能力探测只能靠实际调用（protocol.md §3、§7）。 */
@Serializable
data class HealthDto(
    val ok: Boolean = false,
    @SerialName("started_at") val startedAt: Long? = null,
    val db: Boolean = false,
    @SerialName("users_loaded") val usersLoaded: Int? = null,
    @SerialName("agents_running") val agentsRunning: Int? = null,
)
