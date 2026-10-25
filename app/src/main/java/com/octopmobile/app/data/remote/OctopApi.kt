package com.octopmobile.app.data.remote

import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.data.remote.dto.ApiErrorEnvelope
import com.octopmobile.app.data.remote.dto.CreateThreadResponse
import com.octopmobile.app.data.remote.dto.HistoryResponse
import com.octopmobile.app.data.remote.dto.HitlResumeBody
import com.octopmobile.app.data.remote.dto.LoginRequest
import com.octopmobile.app.data.remote.dto.LoginResponse
import com.octopmobile.app.data.remote.dto.ThreadDto
import com.octopmobile.app.data.remote.dto.UploadResponse
import com.octopmobile.app.data.remote.dto.UserDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Octop REST 客户端（docs/protocol.md §5）。
 *
 * 只包 M1 需要的路由。路径前缀与上游 `_RouterMount` 注册表一致：鉴权在 `/api/auth`，
 * agent / thread / history 都在 `/api/agents`（注意是 `/api/agents/{agent_id}/threads`，
 * 不是 `/api/threads`）。
 *
 * 一个 [OctopApi] 绑定一台实例 —— baseUrl 变了就换实例（spec §20：不做多实例并行管理）。
 */
class OctopApi(
    val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val root: HttpUrl = baseUrl.toHttpUrl()

    suspend fun login(username: String, password: String): ApiResult<LoginResponse> =
        execute(
            request = Request.Builder()
                .url(url(API, AUTH, LOGIN))
                .post(
                    json.encodeToString(
                        LoginRequest.serializer(),
                        LoginRequest(username = username, password = password),
                    ).toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
            deserializer = LoginResponse.serializer(),
        )

    /** 自省：拿当前令牌换用户信息，用来判断本地令牌是否还有效（spec §19 第 7 条）。 */
    suspend fun me(): ApiResult<UserDto> =
        execute(
            request = Request.Builder().url(url(API, AUTH, ME)).build(),
            deserializer = UserDto.serializer(),
        )

    /**
     * 登出。服务端只记审计（204），JWT 无状态、不会立即失效 —— 真正让你登出的是本地清令牌，
     * 所以这里失败也不影响结果，调用方可以忽略返回值。
     */
    suspend fun logout(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url(API, AUTH, LOGOUT))
                .post(EMPTY_BODY)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ApiResult.Ok(Unit)
                } else {
                    ApiResult.Failure(parseError(response.code, response.body?.string().orEmpty()))
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    /** 返回**裸数组**（docs/protocol.md §5.1），不是 `{"items":[...]}`。 */
    suspend fun agents(): ApiResult<List<AgentDto>> =
        execute(
            request = Request.Builder().url(url(API, AGENTS)).build(),
            deserializer = ListSerializer(AgentDto.serializer()),
        )

    suspend fun threads(agentId: String, limit: Int = DEFAULT_PAGE_SIZE): ApiResult<List<ThreadDto>> =
        execute(
            request = Request.Builder()
                .url(
                    url(API, AGENTS, agentId, THREADS)
                        .newBuilder()
                        .addQueryParameter("limit", limit.toString())
                        .build(),
                )
                .build(),
            deserializer = ListSerializer(ThreadDto.serializer()),
        )

    suspend fun history(
        agentId: String,
        threadId: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): ApiResult<HistoryResponse> =
        execute(
            request = Request.Builder()
                .url(
                    url(API, AGENTS, agentId, THREADS, threadId, HISTORY)
                        .newBuilder()
                        .addQueryParameter("limit", limit.toString())
                        .addQueryParameter("offset", offset.toString())
                        .build(),
                )
                .build(),
            deserializer = HistoryResponse.serializer(),
        )

    /**
     * 新建会话（`POST /api/agents/{agent_id}/threads`，201）。
     *
     * 服务端语义是 `ThreadRegistry.reset()`：**新建 thread + 把 dashboard 会话重绑到它**。
     * 旧会话不删，但同一账号的网页端会被切到新会话（docs/protocol.md §5.2）。
     */
    suspend fun createThread(agentId: String): ApiResult<CreateThreadResponse> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, THREADS))
                .post(EMPTY_BODY)
                .build(),
            deserializer = CreateThreadResponse.serializer(),
        )

    /**
     * 附件上传（`POST /api/agents/{agent_id}/upload`）。
     *
     * multipart 字段名固定 `file`（`routers/uploads.py:59-83`）。返回的
     * `workspace_path` / `filename` / `media_type` 是发送时要放进 content block 的那三个。
     */
    suspend fun upload(
        agentId: String,
        filename: String,
        mediaType: String,
        bytes: ByteArray,
    ): ApiResult<UploadResponse> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, UPLOAD))
                .post(
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", filename, bytes.toRequestBody(mediaType.toMediaType()))
                        .build(),
                )
                .build(),
            deserializer = UploadResponse.serializer(),
            // 上传可能包含图片，读超时放宽（默认 30s 对大图偏紧）
            readTimeoutSeconds = UPLOAD_TIMEOUT_SECONDS,
        )

    /**
     * HITL 人工确认续跑（`POST /api/agents/{agent_id}/chat/hitl/resume`，SSE）。
     *
     * 发出的每条 `data:` 原文（与 WS 帧同构的 JSON）—— 由调用方解成
     * [com.octopmobile.app.data.ws.ServerEvent] 后喂进同一条事件管线。
     *
     * **续跑的增量只在这条流上，WS hub 不推**（docs/protocol.md §6.1），所以挂了 WS 也收不到
     * 确认之后的输出，必须消费这条流。
     *
     * 读超时设为 0（不超时）：模型思考期间 SSE 可能长时间没有任何帧。
     */
    fun hitlResume(agentId: String, body: HitlResumeBody): Flow<String> = flow {
        val request = Request.Builder()
            .url(url(API, AGENTS, agentId, CHAT, HITL, RESUME))
            .post(
                json.encodeToString(HitlResumeBody.serializer(), body).toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()

        val streaming = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
        streaming.newCall(request).execute().use { response ->
            val payload = response.body ?: throw IOException("empty response body")
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${payload.string().take(ERROR_BODY_LIMIT)}")
            }
            val source = payload.source()
            while (true) {
                // 只取 `data:` 行；`event: chunk` 与空行都只是分隔（`chat/sse.py:24-32`）。
                val line = source.readUtf8Line() ?: break
                if (line.startsWith(SSE_DATA)) emit(line.removePrefix(SSE_DATA).trim())
            }
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------ 内部

    /**
     * 按**逐段拼接**构造 URL。每个参数是一个独立的路径段，会被正确百分号编码，所以
     * 参数里**不能带 `/`** —— `addPathSegment("api/agents")` 会产出 `/api%2Fagents`，
     * 反代不重写这条路径时就是 404。要拼多级路径就传多个参数（见 [API]、[AGENTS]）。
     */
    private fun url(vararg segments: String): HttpUrl =
        root.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun <T> execute(
        request: Request,
        deserializer: DeserializationStrategy<T>,
        readTimeoutSeconds: Long? = null,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            // newBuilder() 派生的客户端共享连接池与线程池，所以这里只是按需覆盖超时，不产生额外开销。
            val call = (if (readTimeoutSeconds != null) {
                client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
            } else {
                client
            }).newCall(request)
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(parseError(response.code, body))
                }
                val value = try {
                    json.decodeFromString(deserializer, body)
                } catch (e: Exception) {
                    return@withContext ApiResult.Failure(ApiError.Parse(e.message.orEmpty()))
                }
                ApiResult.Ok(value)
            }
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun parseError(status: Int, body: String): ApiError {
        val envelope = runCatching {
            json.decodeFromString(ApiErrorEnvelope.serializer(), body)
        }.getOrNull()
        val details = envelope?.error?.details
        return ApiError.Http(
            status = status,
            code = envelope?.error?.code,
            message = envelope?.error?.message,
            retryAfterSeconds = details?.get("retry_after_seconds").asStringOrNull()?.toLongOrNull(),
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 30

        private const val API = "api"
        private const val AUTH = "auth"
        private const val AGENTS = "agents"

        private const val LOGIN = "login"
        private const val LOGOUT = "logout"
        private const val ME = "me"
        private const val THREADS = "threads"
        private const val HISTORY = "history"
        private const val UPLOAD = "upload"
        private const val CHAT = "chat"
        private const val HITL = "hitl"
        private const val RESUME = "resume"

        /** 附件可能很大，读超时放宽到 2 分钟（默认 30s 传大图会断）。 */
        private const val UPLOAD_TIMEOUT_SECONDS = 120L

        /** SSE 的载荷前缀（`event: chunk\ndata: <JSON>\n\n`）。 */
        private const val SSE_DATA = "data:"

        /** 非 2xx 时只截一段响应体做提示，避免把整页 HTML 塞进错误文案。 */
        private const val ERROR_BODY_LIMIT = 200

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** `POST /api/auth/logout` 没有请求体，但仍需一个 body 才能发 POST。 */
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null, 0, 0)
    }
}

private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
