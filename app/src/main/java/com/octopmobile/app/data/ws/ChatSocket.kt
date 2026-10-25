package com.octopmobile.app.data.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * 聊天 WebSocket（docs/protocol.md §4）。
 *
 * 三件容易做错的事都在这个类里闭环：
 * 1. 鉴权**只能走 query 参数** `?token=`（§8 第 1 条），缺失会被服务端 `close(4001)`；
 * 2. 空闲 30s 发 `ping`，45s 收不到任何入帧就判定链路已死（spec §10）；
 * 3. 断开**不会**取消服务端正在跑的回合，所以重连后必须重新 `subscribe` 才能续收（§8 第 6 条）。
 *
 * 生命周期是「一个实例一次连接」：调用 [close] 之后这个对象就作废了，要重连请新建。
 */
class ChatSocket(
    baseUrl: String,
    private val agentId: String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient,
    private val json: Json,
) {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State

        /** 服务端关闭码或异常。`4001` = 令牌缺失/无效，`4003` = 无权限，`4404` = agent 不存在。 */
        data class Closed(val code: Int, val reason: String?) : State
    }

    private val root = baseUrl.toHttpUrl()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** 订阅过的线程：重连后按这个集合重新 `subscribe`。 */
    private val subscribed = linkedSetOf<String>()

    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var backoffMs = BACKOFF_FIRST_MS

    /**
     * 退避等待的叫醒信号（spec §10「应用回前台立即重连一次」）。
     *
     * 用 `SharedFlow` 而不是 `Channel`：它这里的语义只是「缩短这次等待」，不需要严格消费 ——
     * 多收一次最多让重连早一点发生，没有任何副作用。
     */
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var lastInboundAt = 0L

    @Volatile
    private var wantConnection = false

    fun connect() {
        if (wantConnection) return
        wantConnection = true
        loop = scope.launch { run() }
    }

    fun close() {
        wantConnection = false
        loop = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        scope.cancel()
        _state.value = State.Idle
    }

    fun subscribe(threadId: String) {
        if (threadId.isBlank()) return
        synchronized(subscribed) { subscribed.add(threadId) }
        send(ClientFrame.Subscribe(threadId))
    }

    fun unsubscribe(threadId: String) {
        synchronized(subscribed) { subscribed.remove(threadId) }
    }

    fun ping() {
        send(ClientFrame.Ping)
    }

    /**
     * 让退避中的重连**立刻**发生，不等剩下那段等待（spec §10「应用回前台立即重连一次」）。
     *
     * 只作用于等待期，**不会**去拆一条已经连上的 socket：一条看着还通、其实早断了的链路
     * 由心跳的静默判定负责，硬拆反而会把正在流式的输出切成半截。
     */
    fun reconnectNow() {
        backoffMs = BACKOFF_FIRST_MS
        wake.tryEmit(Unit)
    }

    fun send(frame: ClientFrame): Boolean {
        val ws = socket ?: return false
        return ws.send(json.encodeToString(ClientFrame.serializer(), frame))
    }

    // ------------------------------------------------------------ 连接与重连

    private suspend fun run() {
        while (wantConnection && currentCoroutineContext().isActive) {
            _state.value = State.Connecting

            val token = tokenProvider()
            if (token.isNullOrBlank()) {
                // 没有令牌时重试只是刷日志，等用户重新登录后由调用方再 connect()
                _state.value = State.Closed(CLOSE_MISSING_TOKEN, "missing token")
                wantConnection = false
                break
            }

            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            val ws = client.newWebSocket(request(token), listener(opened, closed))
            socket = ws

            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() } ?: false
            if (!connected) {
                ws.cancel()
                socket = null
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { closed.await() }
                _state.value = State.Closed(CLOSE_CONNECT_FAILED, "connect failed")
                if (!awaitBackoff()) break
                continue
            }

            backoffMs = BACKOFF_FIRST_MS
            lastInboundAt = System.currentTimeMillis()
            _state.value = State.Open
            resubscribe()

            keepAlive(ws, closed)

            socket = null
            if (!wantConnection) break
            if (!awaitBackoff()) break
        }
        _state.value = State.Idle
    }

    /** 心跳与静默判定：以「多久没收到任何入帧」为准，而不是「多久没发 ping」。 */
    private suspend fun keepAlive(ws: WebSocket, closed: CompletableDeferred<Unit>) {
        while (wantConnection && currentCoroutineContext().isActive) {
            val finished = withTimeoutOrNull(PING_INTERVAL_MS) {
                closed.await()
                true
            } ?: false
            if (finished) return

            if (System.currentTimeMillis() - lastInboundAt > SILENCE_TIMEOUT_MS) {
                ws.cancel()
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { closed.await() }
                return
            }
            ws.send(json.encodeToString(ClientFrame.serializer(), ClientFrame.Ping))
        }
    }

    /**
     * 指数退避 + 抖动，上限 30s（spec §10）。返回 false 表示已被要求停止。
     *
     * 等待期间被 [reconnectNow] 叫醒就提前结束这一轮 —— 退避对「网络刚回来」这种情况
     * 没有意义，而应用回前台正是最可能刚刚恢复网络的时候。
     */
    private suspend fun awaitBackoff(): Boolean {
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        val jitter = (wait * JITTER_RATIO * (Random.nextDouble() * 2 - 1)).toLong()
        withTimeoutOrNull((wait + jitter).coerceAtLeast(0L)) { wake.first() }
        return wantConnection && currentCoroutineContext().isActive
    }

    private fun resubscribe() {
        val ids = synchronized(subscribed) { subscribed.toList() }
        ids.forEach { send(ClientFrame.Subscribe(it)) }
    }

    /**
     * 注意**不要**把 scheme 改写成 `ws`/`wss`：OkHttp 的 [okhttp3.HttpUrl] 只认 `http`/`https`，
     * `.scheme("wss")` 会抛 `IllegalArgumentException`，而它抛在 [run] 的协程里没人接，直接崩进程。
     * WebSocket 的 Upgrade 握手由 OkHttp 自己在 `http(s)` 上完成，`newWebSocket` 就该收 http(s) URL。
     */
    private fun request(token: String): Request =
        Request.Builder()
            .url(
                root.newBuilder()
                    .addPathSegments("api/agents")
                    .addPathSegment(agentId)
                    .addPathSegments("chat/ws")
                    .addQueryParameter("token", token)
                    .build(),
            )
            .build()

    private fun listener(
        opened: CompletableDeferred<Boolean>,
        closed: CompletableDeferred<Unit>,
    ) = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastInboundAt = System.currentTimeMillis()
            // 认不出的帧会被解成 ServerEvent.Unknown，绝不因此中断连接（见 ServerEvent 注释）
            _events.tryEmit(ServerEvent.decode(json, text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = State.Closed(code, reason)
            opened.complete(false)
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = State.Closed(response?.code ?: 0, t.message ?: t.javaClass.simpleName)
            opened.complete(false)
            closed.complete(Unit)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val CLOSE_MISSING_TOKEN = 4001
        const val CLOSE_CONNECT_FAILED = 0

        const val EVENT_BUFFER = 256
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
        const val PING_INTERVAL_MS = 30_000L
        const val SILENCE_TIMEOUT_MS = 45_000L
        const val BACKOFF_FIRST_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val JITTER_RATIO = 0.2
    }
}
