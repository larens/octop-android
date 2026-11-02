package com.octopmobile.app.data.repo

import androidx.room.withTransaction
import com.octopmobile.app.data.db.ConversationEventEntity
import com.octopmobile.app.data.db.ConversationTaskEntity
import com.octopmobile.app.data.db.MainConversationEntity
import com.octopmobile.app.data.db.OctopDatabase
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.ConversationEventDto
import com.octopmobile.app.data.remote.dto.ConversationTaskDto
import com.octopmobile.app.data.remote.dto.ConversationTraceDto
import com.octopmobile.app.data.remote.dto.MainConversationDto
import kotlinx.coroutines.flow.Flow

/**
 * 稳定主会话的本地镜像入口。
 *
 * “会话”Tab 用它解析 dashboard 当前绑定的 Thread；任务与有界事件投影继续独立存放，
 * 不会混进用户聊天消息。治理事件只用于任务/追踪，不渲染为聊天气泡。
 */
class MainConversationRepository(
    private val agentId: String,
    private val api: OctopApi,
    private val database: OctopDatabase,
) {
    private val conversationDao = database.mainConversations()
    private val taskDao = database.conversationTasks()
    private val eventDao = database.conversationEvents()

    fun observeConversation(): Flow<MainConversationEntity?> =
        conversationDao.observeByAgent(agentId)

    fun observeTasks(conversationId: String): Flow<List<ConversationTaskEntity>> =
        taskDao.observeByConversation(conversationId)

    fun observeEvents(
        conversationId: String,
        afterSeq: Long = 0,
        limit: Int = DEFAULT_EVENT_LIMIT,
    ): Flow<List<ConversationEventEntity>> =
        eventDao.observeRecent(conversationId, afterSeq, limit)

    suspend fun refreshConversation(): ApiResult<MainConversationEntity> =
        when (val result = api.mainConversation(agentId)) {
            is ApiResult.Failure -> result
            is ApiResult.Ok -> {
                val entity = result.value.toEntity()
                conversationDao.upsert(entity)
                ApiResult.Ok(entity)
            }
        }

    /** Last known stable pointer, useful when the app starts or resumes offline. */
    suspend fun cachedConversation(): MainConversationEntity? = conversationDao.find(agentId)

    suspend fun refreshTasks(): ApiResult<Int> =
        when (val result = api.conversationTasks(agentId)) {
            is ApiResult.Failure -> result
            is ApiResult.Ok -> {
                val tasks = result.value.map { it.toEntity() }
                database.withTransaction { taskDao.upsertAll(tasks) }
                ApiResult.Ok(tasks.size)
            }
        }

    suspend fun refreshEvents(limit: Int = DEFAULT_EVENT_LIMIT): ApiResult<Int> {
        val conversation = conversationDao.find(agentId)
            ?: return ApiResult.Failure(
                com.octopmobile.app.data.remote.ApiError.Http(
                    status = 0,
                    code = "main_conversation_missing",
                    message = "refresh the main conversation before events",
                ),
            )
        val afterSeq = eventDao.maxSeq(conversation.conversationId) ?: 0
        return when (
            val result = api.mainConversationEvents(
                agentId = agentId,
                afterSeq = afterSeq,
                limit = limit,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Ok -> {
                val events = result.value.map { it.toEntity() }
                if (events.isNotEmpty()) {
                    database.withTransaction { eventDao.upsertAll(events) }
                }
                ApiResult.Ok(events.size)
            }
        }
    }

    suspend fun refreshAll(): ApiResult<Unit> {
        when (val conversation = refreshConversation()) {
            is ApiResult.Failure -> return conversation
            is ApiResult.Ok -> Unit
        }
        when (val tasks = refreshTasks()) {
            is ApiResult.Failure -> return tasks
            is ApiResult.Ok -> Unit
        }
        return when (val events = refreshEvents()) {
            is ApiResult.Failure -> events
            is ApiResult.Ok -> ApiResult.Ok(Unit)
        }
    }

    suspend fun trace(traceId: String): ApiResult<ConversationTraceDto> =
        api.conversationTrace(agentId, traceId)

    private fun MainConversationDto.toEntity() = MainConversationEntity(
        conversationId = conversationId,
        agentId = agentId,
        userId = userId,
        routerThreadId = routerThreadId,
        stateJson = state.toString(),
        summary = summary,
        lastConversationSeq = lastConversationSeq,
        createdAt = createdAt.toMillis(),
        updatedAt = updatedAt.toMillis(),
        archivedAt = archivedAt?.toMillis(),
    )

    private fun ConversationTaskDto.toEntity() = ConversationTaskEntity(
        taskId = taskId,
        conversationId = conversationId,
        taskType = taskType,
        title = title,
        subjectKey = subjectKey,
        objective = objective,
        status = status,
        requiresUser = requiresUser,
        capsuleJson = capsule.toString(),
        priority = priority,
        createdAt = createdAt.toMillis(),
        updatedAt = updatedAt.toMillis(),
        expiresAt = expiresAt?.toMillis(),
    )

    private fun ConversationEventDto.toEntity() = ConversationEventEntity(
        eventId = eventId,
        conversationId = conversationId,
        traceId = traceId,
        taskId = taskId,
        runId = runId,
        parentEventId = parentEventId,
        seq = seq,
        schemaVersion = schemaVersion,
        eventType = eventType,
        status = status,
        summary = summary,
        payloadRef = payloadRef,
        payloadJson = payload?.toString(),
        usageJson = usage.toString(),
        createdAt = createdAt.toMillis(),
    )

    private companion object {
        const val DEFAULT_EVENT_LIMIT = 200
    }
}

private fun Long.toMillis(): Long = if (this > 100_000_000_000) this else this * 1000
