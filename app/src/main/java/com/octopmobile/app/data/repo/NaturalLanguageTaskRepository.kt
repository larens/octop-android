package com.octopmobile.app.data.repo

import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.remote.dto.CronJobDto
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.data.ws.ClientFrame
import com.octopmobile.app.data.ws.ServerEvent
import com.octopmobile.app.data.ws.isError
import com.octopmobile.app.data.ws.outputText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Sends one focused turn to an isolated temporary thread, which owns the server's typed cron tools. */
class NaturalLanguageTaskRepository(
    private val socketFactory: () -> ChatSocket,
    private val temporarySessionKey: () -> String = { "android-task:${UUID.randomUUID()}" },
    private val deleteThread: suspend (String) -> ApiResult<Unit> = { ApiResult.Ok(Unit) },
    private val bindConnectors: suspend (String, List<String>) -> ApiResult<Unit> = { _, _ -> ApiResult.Ok(Unit) },
    private val refreshTasks: suspend () -> ApiResult<Int>,
) {
    suspend fun create(
        description: String,
        mcpServers: List<String> = emptyList(),
    ): ApiResult<CronJobDto> = coroutineScope {
        val request = description.trim()
        if (request.isEmpty()) return@coroutineScope ApiResult.Failure(ApiError.Parse("empty task"))

        when (val created = sendInstruction(buildCreateInstruction(request), CREATE_TIMEOUT_MS) { event ->
            if (event.name == CRON_CREATE_TOOL && !event.isError) {
                createdTaskFromToolResult(event.outputText)
            } else {
                null
            }
        }) {
            is ApiResult.Failure -> created
            is ApiResult.Ok -> {
                if (mcpServers.isNotEmpty()) {
                    when (val bound = bindConnectors(created.value.id, mcpServers.distinct())) {
                        is ApiResult.Ok -> Unit
                        is ApiResult.Failure -> return@coroutineScope bound
                    }
                }
                when (val refreshed = refreshTasks()) {
                    is ApiResult.Ok -> created
                    is ApiResult.Failure -> refreshed
                }
            }
        }
    }

    suspend fun modify(task: AutomationTaskEntity, description: String): ApiResult<Unit> = coroutineScope {
        val request = description.trim()
        if (request.isEmpty()) return@coroutineScope ApiResult.Failure(ApiError.Parse("empty task change"))

        when (
            val changed = sendInstruction(buildModifyInstruction(task, request), MODIFY_TIMEOUT_MS) { event ->
                modificationFromToolResult(event.name, event.outputText, task.id)
            }
        ) {
            is ApiResult.Failure -> changed
            is ApiResult.Ok -> when (val refreshed = refreshTasks()) {
                is ApiResult.Ok -> ApiResult.Ok(Unit)
                is ApiResult.Failure -> refreshed
            }
        }
    }

    private suspend fun <T> sendInstruction(
        instruction: String,
        timeoutMillis: Long,
        resultFromTool: (ServerEvent.ToolResult) -> T?,
    ): ApiResult<T> = coroutineScope {
        val socket = socketFactory()
        val sessionKey = temporarySessionKey()
        var temporaryThreadId: String? = null
        try {
            val completed = CompletableDeferred<ApiResult<T>>()
            var toolResult: T? = null
            val connection = async {
                socket.state.filter { it is ChatSocket.State.Open || it is ChatSocket.State.Closed }.first()
            }
            socket.connect()
            val connectionState = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connection.await() }
            connection.cancel()
            when (val state = connectionState) {
                is ChatSocket.State.Open -> Unit
                is ChatSocket.State.Closed -> return@coroutineScope ApiResult.Failure(
                    if (state.code == UNAUTHORIZED_CLOSE_CODE) {
                        ApiError.Http(status = 401, code = null, message = state.reason)
                    } else {
                        ApiError.Network(state.reason ?: "task agent connection closed")
                    },
                )
                else -> return@coroutineScope ApiResult.Failure(ApiError.Network("task agent connection timeout"))
            }

            val events = async(start = CoroutineStart.UNDISPATCHED) {
                socket.events.collect { event ->
                    if (!event.threadId.isNullOrBlank()) temporaryThreadId = event.threadId
                    when (event) {
                        is ServerEvent.ToolResult -> if (!event.isError && toolResult == null) {
                            toolResult = resultFromTool(event)
                        }
                        is ServerEvent.TurnEnded -> {
                            val value = toolResult
                            completed.complete(
                                if (!event.failed && !event.hitlPaused && value != null) {
                                    ApiResult.Ok(value)
                                } else {
                                    ApiResult.Failure(ApiError.Parse("agent did not complete cron task operation"))
                                },
                            )
                        }
                        is ServerEvent.Error -> completed.complete(ApiResult.Failure(ApiError.Parse(event.message)))
                        else -> Unit
                    }
                }
            }
            val sent = socket.send(
                ClientFrame.UserTurn(
                    text = instruction,
                    sessionKey = sessionKey,
                    conversationMode = CRAFT_MODE,
                    delivery = AUTO_DELIVERY,
                    clientPendingId = "android-task-${UUID.randomUUID().toString().take(8)}",
                ),
            )
            if (!sent) {
                events.cancel()
                return@coroutineScope ApiResult.Failure(ApiError.Network("task agent disconnected"))
            }

            val result = withTimeoutOrNull(timeoutMillis) { completed.await() }
            if (result == null) {
                events.cancel()
                return@coroutineScope ApiResult.Failure(ApiError.Network("task agent timeout"))
            }
            events.cancel()
            result
        } finally {
            socket.close()
            temporaryThreadId?.let { threadId ->
                repeat(TEMP_THREAD_DELETE_ATTEMPTS) { attempt ->
                    if (deleteThread(threadId) is ApiResult.Ok) return@let
                    if (attempt + 1 < TEMP_THREAD_DELETE_ATTEMPTS) kotlinx.coroutines.delay(250L)
                }
            }
        }
    }

    internal fun buildInstruction(request: String): String = buildCreateInstruction(request)

    private fun buildCreateInstruction(request: String): String = """
        用户要用自然语言新建一条定时任务：$request

        当前时间是 ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}。
        请提炼一个简短标题，并从原文准确提取触发时间和每次执行的待办内容，然后立即调用 cronjob_create 创建任务。
        固定提醒使用 task_type=text；每次需要查询、汇总或分析最新数据时使用 task_type=agent。
        不要扩写用户未要求的事项。如果缺少明确的执行时间或待办内容，不要调用工具，只用一句话说明还缺什么。
    """.trimIndent()

    internal fun buildModifyInstruction(task: AutomationTaskEntity, request: String): String = """
        用户正在修改一条已经存在的定时任务。

        只允许操作这一条任务，任务 ID 是：${task.id}
        当前标题：${task.title}
        当前触发规则：${task.scheduleRule}
        当前执行内容：${task.prompt}
        当前状态：${task.status}
        用户要求：$request

        当前时间是 ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}。
        根据用户要求调用 cronjob_update 或 cronjob_delete，并且必须使用上面的准确任务 ID。
        暂停任务使用 cronjob_update 将 enabled 设为 false；恢复任务将 enabled 设为 true。
        没有要求修改的标题、触发规则或执行内容必须保持不变。绝对不要调用 cronjob_create，也不要操作其他任务。
    """.trimIndent()

    internal fun createdTaskFromToolResult(raw: String?): CronJobDto? = raw
        ?.let { runCatching { TASK_RESULT_JSON.decodeFromString(CronJobDto.serializer(), it) }.getOrNull() }
        ?.takeIf { it.id.isNotBlank() && it.trigger.isNotBlank() && it.prompt.isNotBlank() }

    internal fun modificationFromToolResult(toolName: String?, raw: String?, expectedId: String): Unit? {
        val resultId = when (toolName) {
            CRON_UPDATE_TOOL -> raw
                ?.let { runCatching { TASK_RESULT_JSON.decodeFromString(CronJobDto.serializer(), it) }.getOrNull() }
                ?.id
            CRON_DELETE_TOOL -> raw
                ?.let { runCatching { TASK_RESULT_JSON.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?.get("deleted")
                ?.jsonPrimitive
                ?.content
            else -> null
        }
        return Unit.takeIf { resultId == expectedId }
    }

    private companion object {
        val TASK_RESULT_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        const val CRON_CREATE_TOOL = "cronjob_create"
        const val CRON_UPDATE_TOOL = "cronjob_update"
        const val CRON_DELETE_TOOL = "cronjob_delete"
        const val CRAFT_MODE = "craft"
        const val AUTO_DELIVERY = "auto"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val CREATE_TIMEOUT_MS = 90_000L
        const val MODIFY_TIMEOUT_MS = 90_000L
        const val UNAUTHORIZED_CLOSE_CODE = 4001
        const val TEMP_THREAD_DELETE_ATTEMPTS = 3
    }
}
