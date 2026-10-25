package com.octopmobile.app.data.remote

/**
 * REST 调用的统一结果。**所有失败都在这里被分类**，UI 层不再面对裸的 `IOException`
 * 或 `HTTP 500`（docs/spec.md §9.1）。
 */
sealed interface ApiResult<out T> {

    data class Ok<T>(val value: T) : ApiResult<T>

    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {

    /**
     * 服务端明确拒绝：`{"error":{"code":"...","message":"...","details":{}}}`。
     *
     * [code] 是上游的机器可读码（`AUTH_FAILED` / `LOGIN_LOCKED` / `SETUP_REQUIRED` …），
     * [status] 是 HTTP 状态码。两者都要留着 —— 401 上的 `AUTH_FAILED` 是「密码错」，
     * 而 401 的 `FORBIDDEN` 才是「登录已失效」，只看状态码分不出来。
     */
    data class Http(
        val status: Int,
        val code: String?,
        val message: String?,
        val retryAfterSeconds: Long? = null,
    ) : ApiError

    /** 没走到服务端：DNS / 超时 / 连接被拒。 */
    data class Network(val message: String) : ApiError

    /** 走到了，但响应体不是我们认识的形状（上游改结构了）。 */
    data class Parse(val message: String) : ApiError
}

/** 令牌失效：用户需要重新登录，而不是看到一堆解析错误。 */
val ApiError.isUnauthorized: Boolean
    get() = this is ApiError.Http && status == 401
