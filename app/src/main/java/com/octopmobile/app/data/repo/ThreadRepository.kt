package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.ThreadDao
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.ConversationMode
import com.octopmobile.app.data.remote.dto.ThreadDto
import kotlinx.coroutines.flow.Flow

/**
 * 会话列表（docs/spec.md §6 `data/repo`）。
 *
 * 读路径是「Room 出列表，服务端只负责回填」：UI 订阅 [observeThreads]，[refresh] 把
 * `GET /threads` 的结果写进 Room，界面自然刷新。这样冷启动不必等网络就有内容（§12）。
 */
class ThreadRepository(
    private val agentId: String,
    private val api: OctopApi,
    private val threadDao: ThreadDao,
) {

    fun observeThreads(): Flow<List<ThreadEntity>> = threadDao.observeByAgent(agentId)

    /**
     * 拉一页会话列表并覆盖本地缓存。返回本次落库的条数，供 UI 区分「没有会话」与「拉取失败」。
     */
    suspend fun refresh(limit: Int = OctopApi.DEFAULT_PAGE_SIZE): ApiResult<Int> =
        when (val result = api.threads(agentId, limit)) {
            is ApiResult.Ok -> {
                val entities = result.value.map { it.toEntity(agentId) }
                threadDao.upsertAll(entities)
                ApiResult.Ok(entities.size)
            }

            is ApiResult.Failure -> result
        }

    /**
     * 新建会话，成功返回新的 `thread_id`。
     *
     * 服务端语义是 `ThreadRegistry.reset()`：新建一条 thread 并**把 dashboard 会话重绑过去**，
     * 旧会话仍在列表里（docs/protocol.md §5.2）。所以本地先插一条空会话顶上 —— 点完「+」
     * 立刻能在列表里看到它，不必等下一次刷新。
     */
    suspend fun create(): ApiResult<String> =
        when (val result = api.createThread(agentId)) {
            is ApiResult.Ok -> {
                val now = System.currentTimeMillis()
                threadDao.upsert(
                    ThreadEntity(
                        threadId = result.value.threadId,
                        agentId = agentId,
                        title = null,
                        lastMessage = null,
                        pinned = false,
                        lastActiveAt = now,
                        createdAt = now,
                        unreadCount = 0,
                        modelRef = null,
                        conversationMode = ConversationMode.CRAFT,
                        turnActive = false,
                        awaitingUser = false,
                    ),
                )
                ApiResult.Ok(result.value.threadId)
            }

            is ApiResult.Failure -> result
        }
}

/**
 * 服务端时间戳是 epoch **秒**，本地统一毫秒（docs/spec.md §8.2）。
 *
 * `last_active` 缺失时退到 `created_at` —— 排序键不能是 0，否则会话会全部堆到列表底部。
 * 列表接口不返回最后一条消息，所以 [ThreadEntity.lastMessage] 留空，由会话页回填。
 */
private fun ThreadDto.toEntity(agentId: String): ThreadEntity {
    val activeSeconds = lastActive ?: createdAt ?: 0L
    return ThreadEntity(
        threadId = threadId,
        agentId = agentId,
        title = title?.takeIf { it.isNotBlank() },
        lastMessage = null,
        pinned = pinned,
        lastActiveAt = activeSeconds * 1000,
        createdAt = (createdAt ?: activeSeconds) * 1000,
        unreadCount = 0,
        modelRef = modelRef,
        conversationMode = conversationMode,
        turnActive = turnActive,
        awaitingUser = awaitingUser,
    )
}
