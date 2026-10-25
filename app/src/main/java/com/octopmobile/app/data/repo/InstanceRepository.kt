package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.InstanceDao
import com.octopmobile.app.data.db.InstanceEntity
import com.octopmobile.app.data.prefs.SecurePrefs
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.data.remote.dto.UserDto
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.data.ws.ServerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 实例级会话：登录、登出、agent 目录、能力探测（docs/spec.md §9.5）。
 *
 * 绑定一台实例（[baseUrl]），与 [OctopApi] 同生命周期。
 */
class InstanceRepository(
    private val baseUrl: String,
    private val api: OctopApi,
    private val securePrefs: SecurePrefs,
    private val instanceDao: InstanceDao,
    private val socketFactory: (agentId: String) -> ChatSocket,
) {

    /** 能力结论。`native` 之外的取值一律走 WebView 兜底通道。 */
    enum class Capability(val wire: String) {
        NATIVE("native"),
        WEBVIEW_ONLY("webview_only"),
        ;

        companion object {
            fun from(wire: String?): Capability =
                entries.firstOrNull { it.wire == wire } ?: NATIVE
        }
    }

    /**
     * 登录成功后立刻把令牌写进加密存储 —— 它是后续所有请求（含 WS 的 query 参数）的唯一凭据。
     */
    suspend fun login(username: String, password: String): ApiResult<UserDto> =
        when (val result = api.login(username, password)) {
            is ApiResult.Failure -> result

            is ApiResult.Ok -> {
                val response = result.value
                if (response.accessToken.isBlank()) {
                    ApiResult.Failure(ApiError.Http(status = 200, code = null, message = "empty access_token"))
                } else {
                    securePrefs.saveSession(
                        baseUrl = baseUrl,
                        token = response.accessToken,
                        username = response.user?.username ?: username,
                        displayName = response.user?.displayName,
                    )
                    ApiResult.Ok(response.user ?: UserDto(username = username))
                }
            }
        }

    /** 冷启动校验本地令牌（spec §19 第 7 条）：`/me` 通不过就回登录页。 */
    suspend fun verifySession(): Boolean = api.me() is ApiResult.Ok

    suspend fun agents(): ApiResult<List<AgentDto>> = api.agents()

    suspend fun logout() {
        api.logout()
        securePrefs.clear()
    }

    /**
     * 已持久化的能力判定。`null` 表示**这台实例还没探测过** —— 与「探测过且结论为 `native`」
     * 是两回事，调用方据此决定要不要跑一次昂贵的 WS 探测（docs/spec.md §4）。
     */
    suspend fun recordedCapability(): Capability? =
        instanceDao.find(baseUrl)?.capability?.let { Capability.from(it) }

    /** agent 列表为空这类「探测项 3 直接失败」的情况，不必再走 [probe] 就能定判定。 */
    suspend fun markWebviewOnly(): Capability = record(Capability.WEBVIEW_ONLY)

    /**
     * 能力探测（docs/protocol.md §7）。
     *
     * 第 1~3 项（health / login / agents 非空）在连接向导与登录阶段已经验证过，走到这里时必然成立；
     * 这里只做第 4 项（`threads?limit=1`）与第 5 项（WS 握手 + `subscribe` 收到 `turn_status`）。
     */
    suspend fun probe(agentId: String): Capability {
        val threads = when (val result = api.threads(agentId, limit = 1)) {
            is ApiResult.Ok -> result.value
            is ApiResult.Failure -> return record(Capability.WEBVIEW_ONLY)
        }

        // 第 5 项失败重试 1 次。
        val threadId = threads.firstOrNull()?.threadId
        repeat(WS_PROBE_ATTEMPTS) {
            if (probeSocket(agentId, threadId)) return record(Capability.NATIVE)
        }
        return record(Capability.WEBVIEW_ONLY)
    }

    /**
     * WS 探测。`4001/4003/4404` 这类关闭码会体现为 [ChatSocket.State.Closed]，一律算失败。
     *
     * 没有线程可订阅时只能验证握手（agent 刚建、还没聊过），这也是协议允许的边界。
     */
    private suspend fun probeSocket(agentId: String, threadId: String?): Boolean {
        val socket = socketFactory(agentId)
        return try {
            socket.connect()
            val settled = withTimeoutOrNull(WS_PROBE_TIMEOUT_MS) {
                socket.state.first { it is ChatSocket.State.Open || it is ChatSocket.State.Closed }
            }
            when (settled) {
                is ChatSocket.State.Open -> {
                    if (threadId == null) {
                        true
                    } else {
                        // 先挂上收集再订阅：`subscribe` 之后服务端立刻回 `turn_status`，
                        // 顺序反了就会漏掉这一帧（SharedFlow replay=0）。
                        coroutineScope {
                            val seen = CompletableDeferred<Unit>()
                            val collector = launch {
                                socket.events.collect { if (it is ServerEvent.TurnStatus) seen.complete(Unit) }
                            }
                            socket.subscribe(threadId)
                            val ok = withTimeoutOrNull(WS_PROBE_TIMEOUT_MS) { seen.await() } != null
                            collector.cancel()
                            ok
                        }
                    }
                }

                else -> false
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun record(capability: Capability): Capability {
        val existing = instanceDao.find(baseUrl)
        instanceDao.upsert(
            InstanceEntity(
                baseUrl = baseUrl,
                displayName = existing?.displayName,
                capability = capability.wire,
                lastConnectedAt = System.currentTimeMillis(),
            ),
        )
        return capability
    }

    private companion object {
        const val WS_PROBE_ATTEMPTS = 2
        const val WS_PROBE_TIMEOUT_MS = 8_000L
    }
}
