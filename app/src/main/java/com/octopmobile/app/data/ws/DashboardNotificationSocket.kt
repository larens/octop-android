package com.octopmobile.app.data.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class TodayCardPush(val agentId: String, val cardId: String)
data class ThreadPush(val agentId: String, val threadId: String, val text: String)

/** Foreground-only dashboard push connection. Unknown notification types are ignored. */
class DashboardNotificationSocket(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val root = baseUrl.toHttpUrl()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _todayCards = MutableSharedFlow<TodayCardPush>(extraBufferCapacity = 16)
    val todayCards: SharedFlow<TodayCardPush> = _todayCards.asSharedFlow()
    private val _threadPushes = MutableSharedFlow<ThreadPush>(extraBufferCapacity = 16)
    val threadPushes: SharedFlow<ThreadPush> = _threadPushes.asSharedFlow()

    private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile private var running = false
    @Volatile private var lastInboundAt = 0L

    fun connect() {
        if (running) return
        running = true
        loop = scope.launch { connectLoop() }
    }

    fun close() {
        running = false
        loop?.cancel()
        loop = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        scope.cancel()
    }

    private suspend fun connectLoop() {
        var backoffMs = BACKOFF_FIRST_MS
        while (running && currentCoroutineContext().isActive) {
            val token = tokenProvider()?.takeIf(String::isNotBlank) ?: break
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            val current = client.newWebSocket(request(token), listener(opened, closed))
            socket = current
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() } ?: false
            if (!connected) {
                current.cancel()
                socket = null
                if (running) delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                continue
            }
            backoffMs = BACKOFF_FIRST_MS
            lastInboundAt = System.currentTimeMillis()
            while (running && currentCoroutineContext().isActive) {
                val ended = withTimeoutOrNull(PING_INTERVAL_MS) {
                    closed.await()
                    true
                } ?: false
                if (ended) break
                if (System.currentTimeMillis() - lastInboundAt > SILENCE_TIMEOUT_MS) {
                    current.cancel()
                    break
                }
                current.send("{\"type\":\"ping\"}")
            }
            socket = null
            if (running) delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
    }

    private fun request(token: String): Request = Request.Builder()
        .url(
            root.newBuilder()
                .addPathSegments("api/notifications/ws")
                .addQueryParameter("token", token)
                .build(),
        )
        .build()

    private fun listener(opened: CompletableDeferred<Boolean>, closed: CompletableDeferred<Unit>) =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundAt = System.currentTimeMillis()
                decodeTodayCardPush(json, text)?.let(_todayCards::tryEmit)
                decodeThreadPush(json, text)?.let(_threadPushes::tryEmit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                opened.complete(false)
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                closed.complete(Unit)
            }
        }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val PING_INTERVAL_MS = 30_000L
        const val SILENCE_TIMEOUT_MS = 45_000L
        const val BACKOFF_FIRST_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
    }
}

internal fun decodeTodayCardPush(json: Json, text: String): TodayCardPush? {
    val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    if (frame["type"]?.jsonPrimitive?.content != "dashboard_push") return null
    if (frame["event"]?.jsonPrimitive?.content != "today.card_changed") return null
    val agentId = frame["agent_id"]?.jsonPrimitive?.content.orEmpty()
    val cardId = frame["card_id"]?.jsonPrimitive?.content.orEmpty()
    return if (agentId.isBlank() || cardId.isBlank()) null else TodayCardPush(agentId, cardId)
}

internal fun decodeThreadPush(json: Json, text: String): ThreadPush? {
    val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    if (frame["type"]?.jsonPrimitive?.content != "dashboard_push") return null
    if (frame["event"]?.jsonPrimitive?.content == "today.card_changed") return null
    val agentId = frame["agent_id"]?.jsonPrimitive?.content.orEmpty()
    val threadId = frame["thread_id"]?.jsonPrimitive?.content.orEmpty()
    val content = frame["text"]?.jsonPrimitive?.content.orEmpty()
    return if (agentId.isBlank() || threadId.isBlank()) {
        null
    } else {
        ThreadPush(agentId, threadId, content)
    }
}
