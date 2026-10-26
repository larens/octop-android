package com.octopmobile.app.data.remote

import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.data.remote.dto.ApiErrorEnvelope
import com.octopmobile.app.data.remote.dto.ConversationMode
import com.octopmobile.app.data.remote.dto.CreateThreadResponse
import com.octopmobile.app.data.remote.dto.CronJobCreateBody
import com.octopmobile.app.data.remote.dto.CronJobDto
import com.octopmobile.app.data.remote.dto.CronJobPatchBody
import com.octopmobile.app.data.remote.dto.ConnectorInstanceDto
import com.octopmobile.app.data.remote.dto.HistoryResponse
import com.octopmobile.app.data.remote.dto.HitlResumeBody
import com.octopmobile.app.data.remote.dto.LoginRequest
import com.octopmobile.app.data.remote.dto.LoginResponse
import com.octopmobile.app.data.remote.dto.ResolvedModelDto
import com.octopmobile.app.data.remote.dto.RenameThreadBody
import com.octopmobile.app.data.remote.dto.ThreadDto
import com.octopmobile.app.data.remote.dto.TrajectoryHistoryDto
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    suspend fun logout(): ApiResult<Unit> =
        executeUnit(
            Request.Builder()
                .url(url(API, AUTH, LOGOUT))
                .post(EMPTY_BODY)
                .build(),
        )

    /** 返回**裸数组**（docs/protocol.md §5.1），不是 `{"items":[...]}`。 */
    suspend fun agents(): ApiResult<List<AgentDto>> =
        execute(
            request = Request.Builder().url(url(API, AGENTS)).build(),
            deserializer = ListSerializer(AgentDto.serializer()),
        )

    /** 当前用户可见的可选模型（`GET /api/providers/resolved`）。 */
    suspend fun resolvedModels(): ApiResult<List<ResolvedModelDto>> =
        execute(
            request = Request.Builder().url(url(API, PROVIDERS, RESOLVED)).build(),
            deserializer = ListSerializer(ResolvedModelDto.serializer()),
        )

    /** 当前用户在服务端已经安装且可见的连接器实例。 */
    suspend fun connectorInstances(): ApiResult<List<ConnectorInstanceDto>> =
        execute(
            request = Request.Builder().url(url(API, CONNECTOR_INSTANCES)).build(),
            deserializer = ListSerializer(ConnectorInstanceDto.serializer()),
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
     * 会话轨迹（`GET /api/agents/{agent_id}/threads/{thread_id}/trajectory`，
     * `routers/chat/trajectory.py:239-283`）。
     *
     * 客户端只用到 `kinds=compacted` 这一种事件，拿它画消息流里的压缩分隔线
     * （docs/spec.md §8.2）。**压缩不推 WS**（`infra/gateway/ws/` 里没有任何 `compacted`
     * 事件），所以这是该信息的唯一来源。
     *
     * [beforeSeq] 是向更早翻页的游标，来自上一页的 `next_before_seq`。
     */
    suspend fun trajectory(
        agentId: String,
        threadId: String,
        kinds: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        beforeSeq: Int? = null,
    ): ApiResult<TrajectoryHistoryDto> =
        execute(
            request = Request.Builder()
                .url(
                    url(API, AGENTS, agentId, THREADS, threadId, TRAJECTORY)
                        .newBuilder()
                        .addQueryParameter("limit", limit.toString())
                        .apply {
                            if (!kinds.isNullOrBlank()) addQueryParameter("kinds", kinds)
                            if (beforeSeq != null) addQueryParameter("before_seq", beforeSeq.toString())
                        }
                        .build(),
                )
                .build(),
            deserializer = TrajectoryHistoryDto.serializer(),
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
     * 改名 / 置顶（`PATCH /api/agents/{agent_id}/threads/{thread_id}`，200）。
     *
     * 上游返回更新后的 thread 对象（`chat/history.py:546-560`），这里**不解析** —— 本地那份自己写，
     * 下次列表刷新再和服务端对齐。`null` 字段不会进请求体（`Json.explicitNulls = false`），
     * 所以「只改 pinned」的请求不会顺带把 `title` 置空（docs/protocol.md §5.2）。
     */
    suspend fun patchThread(
        agentId: String,
        threadId: String,
        body: RenameThreadBody,
    ): ApiResult<Unit> =
        executeUnit(
            Request.Builder()
                .url(url(API, AGENTS, agentId, THREADS, threadId))
                .patch(
                    json.encodeToString(RenameThreadBody.serializer(), body)
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
        )

    /** 删除会话（`DELETE /api/agents/{agent_id}/threads/{thread_id}`，204）。 */
    suspend fun deleteThread(agentId: String, threadId: String): ApiResult<Unit> =
        executeUnit(
            Request.Builder()
                .url(url(API, AGENTS, agentId, THREADS, threadId))
                .delete()
                .build(),
        )

    /** 删除选中消息所属的整轮问答，并同步从服务端模型上下文中移除。 */
    suspend fun deleteConversationTurn(
        agentId: String,
        threadId: String,
        messageId: String,
    ): ApiResult<Unit> =
        executeUnit(
            Request.Builder()
                .url(url(API, AGENTS, agentId, THREADS, threadId, TURNS, messageId))
                .delete()
                .build(),
        )

    /**
     * 标记会话已读（`POST /api/agents/{agent_id}/threads/{thread_id}/read`，204）。
     *
     * 上游每个会话都有自己的 `unread_count`，但**只有 agent 级聚合**会随 `GET /api/agents` 返回，
     * thread 列表里没有这个字段（docs/protocol.md §5.2）。所以客户端的未读是自己攒的，
     * 这个接口只负责把服务端那一份一起清掉，免得下次刷新又翻出来。
     */
    suspend fun markThreadRead(agentId: String, threadId: String): ApiResult<Unit> =
        executeUnit(
            Request.Builder()
                .url(url(API, AGENTS, agentId, THREADS, threadId, READ))
                .post(EMPTY_BODY)
                .build(),
        )

    /**
     * 记忆页顶部「用户画像」：服务端已经整理好的终端视图（`GET .../memory/terminal/about_me`）。
     *
     * 这类 endpoint 直接把 JSON-RPC 的结果原样透出来，结构可能随 `octop-memory` 版本演进；
     * 因此这里先收成 [JsonElement]，由上层按需宽松解析，而不是在网络层强绑一套易碎 DTO。
     */
    suspend fun memoryTerminalAboutMe(agentId: String, limit: Int = DEFAULT_MEMORY_LIMIT): ApiResult<JsonElement> =
        execute(
            request = Request.Builder()
                .url(
                    url(API, AGENTS, agentId, MEMORY, TERMINAL, ABOUT_ME)
                        .newBuilder()
                        .addQueryParameter("limit", limit.toString())
                        .build(),
                )
                .build(),
            deserializer = JsonElement.serializer(),
        )

    /** 终端视图：当前关注。 */
    suspend fun memoryTerminalCurrentFocus(agentId: String): ApiResult<JsonElement> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, MEMORY, TERMINAL, CURRENT_FOCUS))
                .build(),
            deserializer = JsonElement.serializer(),
        )

    /** 终端视图：你告诉过我的事。 */
    suspend fun memoryTerminalThingsYouToldMe(agentId: String): ApiResult<JsonElement> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, MEMORY, TERMINAL, THINGS_YOU_TOLD_ME))
                .build(),
            deserializer = JsonElement.serializer(),
        )

    /** 终端视图：实体概览。 */
    suspend fun memoryTerminalEntities(agentId: String): ApiResult<JsonElement> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, MEMORY, TERMINAL, ENTITIES))
                .build(),
            deserializer = JsonElement.serializer(),
        )

    /**
     * 情绪日记 / Episode 列表（`POST .../memory/episodes/list`）。
     *
     * 与服务端保持最小协议：这里只带 `limit`，不替客户端预先加情绪或时间过滤，让记忆页先把
     * 最近沉淀出来的情绪片段完整呈现。
     */
    suspend fun memoryEpisodes(agentId: String, limit: Int = DEFAULT_DIARY_LIMIT): ApiResult<JsonElement> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, MEMORY, EPISODES, LIST))
                .post(
                    buildJsonObject {
                        put("limit", limit)
                    }.toString().toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
            deserializer = JsonElement.serializer(),
        )

    /**
     * 结构化原子记忆（事实 / 偏好 / 决策等）列表，用来填「记忆沉淀」。
     *
     * 取最近更新的少量条目即可；完整检索留给后续做独立详情页。
     */
    suspend fun memoryAtoms(agentId: String, limit: Int = DEFAULT_MEMORY_LIMIT): ApiResult<JsonElement> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, MEMORY, ATOMS, LIST))
                .post(
                    buildJsonObject {
                        put("limit", limit)
                        put("order_by", "created_at")
                        put("order", "desc")
                        put("include_deprecated", false)
                    }.toString().toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
            deserializer = JsonElement.serializer(),
        )

    // ------------------------------------------------------------ 定时任务（cron）

    /**
     * 列出该 agent 的定时任务（`GET /api/agents/{agent_id}/cron`，裸数组）。
     *
     * 没有权限的用户拿到的是**空数组**而不是 403（`routers/cron.py:117-122`），
     * 所以「任务页是空的」既可能是真没有、也可能是没权限，客户端不做区分。
     */
    suspend fun cronJobs(agentId: String): ApiResult<List<CronJobDto>> =
        execute(
            request = Request.Builder().url(url(API, AGENTS, agentId, CRON)).build(),
            deserializer = ListSerializer(CronJobDto.serializer()),
        )

    /** 新建定时任务（`POST /api/agents/{agent_id}/cron`，201）。返回服务端生成的完整对象。 */
    suspend fun createCronJob(agentId: String, body: CronJobCreateBody): ApiResult<CronJobDto> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, CRON))
                .post(json.encodeToString(CronJobCreateBody.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = CronJobDto.serializer(),
        )

    /** 更新定时任务（`PATCH …/cron/{cron_id}`）。上游返回更新后的对象，这里解析出来直接回写本地。 */
    suspend fun patchCronJob(
        agentId: String,
        cronId: String,
        body: CronJobPatchBody,
    ): ApiResult<CronJobDto> =
        execute(
            request = Request.Builder()
                .url(url(API, AGENTS, agentId, CRON, cronId))
                .patch(json.encodeToString(CronJobPatchBody.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = CronJobDto.serializer(),
        )

    /** 删除定时任务（`DELETE …/cron/{cron_id}`，204）。 */
    suspend fun deleteCronJob(agentId: String, cronId: String): ApiResult<Unit> =
        executeUnit(
            Request.Builder()
                .url(url(API, AGENTS, agentId, CRON, cronId))
                .delete()
                .build(),
        )

    /**
     * 立刻跑一次（`POST …/cron/{cron_id}/run-now`，204）。
     *
     * 不影响原有排期：服务端在 APScheduler 之外单独触发一次投递。
     */
    suspend fun runCronNow(agentId: String, cronId: String): ApiResult<Unit> =
        executeUnit(
            Request.Builder()
                .url(url(API, AGENTS, agentId, CRON, cronId, RUN_NOW))
                .post(EMPTY_BODY)
                .build(),
        )

    /**
     * 附件上传（`POST /api/agents/{agent_id}/upload`）。
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
     * 下载一个附件（docs/spec.md §13）。
     *
     * [absoluteUrl] **必须是绝对地址** —— 上游给的是相对引用（`workspace/download?path=…`），
     * 归一化在 `data/attachments/AttachmentRefs` 里做。这些路由要求 `Authorization: Bearer`
     * （`routers/workspace.py:339` 的 `Depends(current_user)`），而本客户端正是带鉴权的那一个。
     */
    suspend fun download(absoluteUrl: String): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(absoluteUrl.toHttpUrl()).build()
            val call = client.newBuilder()
                .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .newCall(request)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(
                        parseError(response.code, response.body?.string().orEmpty()),
                    )
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    ApiResult.Failure(ApiError.Parse("empty attachment body"))
                } else {
                    ApiResult.Ok(bytes)
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

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

    /**
     * 只关心成功/失败、不需要响应体的一类请求（`logout` / `PATCH` / `DELETE` / `read`）。
     *
     * 这些路由有的返回 204 空体、有的返回 JSON —— [execute] 必须解码，对空体没法用；
     * 而这里两个都当「成功」处理，需要的内容由调用方自己写本地。
     */
    private suspend fun executeUnit(request: Request): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
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

        /**
         * 会话列表全量同步用的上限。服务端列表路由只认 `limit`、没有分页游标，
         * 取的还是「最新在前」的一页；返回条数 < limit 的短页才等于全量，
         * 客户端才能放心做本地收敛（见 ThreadRepository.refresh）。
         */
        const val FULL_SYNC_LIMIT = 2000

        private const val API = "api"
        private const val AUTH = "auth"
        private const val AGENTS = "agents"
        private const val PROVIDERS = "providers"
        private const val CONNECTOR_INSTANCES = "connector-instances"
        private const val RESOLVED = "resolved"

        private const val LOGIN = "login"
        private const val LOGOUT = "logout"
        private const val ME = "me"
        private const val THREADS = "threads"
        private const val TURNS = "turns"
        private const val HISTORY = "history"
        private const val TRAJECTORY = "trajectory"
        private const val READ = "read"
        private const val MEMORY = "memory"
        private const val TERMINAL = "terminal"
        private const val ABOUT_ME = "about_me"
        private const val CURRENT_FOCUS = "current_focus"
        private const val THINGS_YOU_TOLD_ME = "things_you_told_me"
        private const val ENTITIES = "entities"
        private const val EPISODES = "episodes"
        private const val ATOMS = "atoms"
        private const val LIST = "list"
        private const val UPLOAD = "upload"
        private const val CRON = "cron"
        private const val RUN_NOW = "run-now"
        private const val CHAT = "chat"
        private const val HITL = "hitl"
        private const val RESUME = "resume"

        /** 附件可能很大，读超时放宽到 2 分钟（默认 30s 传大图会断）。 */
        private const val UPLOAD_TIMEOUT_SECONDS = 120L

        /** 画像与档案只需要首页摘要；日记列表多取一些，支持移动端直接翻阅。 */
        private const val DEFAULT_MEMORY_LIMIT = 6
        private const val DEFAULT_DIARY_LIMIT = 60

        /** 下载同理，再宽一点：服务端可能要先从 workspace 读文件再回传（docs/spec.md §13）。 */
        private const val DOWNLOAD_TIMEOUT_SECONDS = 240L

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
