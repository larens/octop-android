package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.ThreadDao
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.ConversationMode
import com.octopmobile.app.data.remote.dto.ResolvedModelDto
import com.octopmobile.app.data.remote.dto.RenameThreadBody
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
     * 拉会话列表并合并进本地缓存。返回本次落库的条数，供 UI 区分「没有会话」与「拉取失败」。
     *
     * 是**合并**而不是覆盖：`ThreadDto` 既不含最后一条消息，也不含未读数（字段清单见
     * docs/protocol.md §5.2），整体 `upsert` 会把本地攒的东西抹掉。
     *
     * 默认按 [OctopApi.FULL_SYNC_LIMIT] 拉全量：服务端没有翻页游标，返回短页就等于
     * 拿到了全部会话，此时顺手把服务端已不存在的本地行删掉（换过实例、服务端清理
     * 过都可能留下孤儿），让两端会话队列保持一致。离线时刷新失败，本地缓存原样保留。
     */
    suspend fun refresh(limit: Int = OctopApi.FULL_SYNC_LIMIT): ApiResult<Int> =
        when (val result = api.threads(agentId, limit)) {
            is ApiResult.Ok -> {
                val local = threadDao.listByAgent(agentId).associateBy { it.threadId }
                val entities = result.value.map { it.toEntity(agentId, local[it.threadId]) }
                threadDao.upsertAll(entities)
                // 只有「这一页没满」才能确定是全量；满页说明服务端可能还有更多，不能动手。
                if (result.value.size < limit) {
                    if (result.value.isEmpty()) {
                        threadDao.deleteAllByAgent(agentId)
                    } else {
                        threadDao.deleteMissing(agentId, result.value.map { it.threadId })
                    }
                }
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

    /**
     * 置顶 / 取消置顶（`PATCH pinned`，docs/protocol.md §5.2）。
     *
     * 一律**服务端先成功、再写本地**：列表完全由 Room 驱动，写库那一刻界面就更新了，
     * 不需要在 UI 上另存一份「正在置顶」的状态；失败时界面保持原样，只提示。
     */
    suspend fun setPinned(threadId: String, pinned: Boolean): ApiResult<Unit> =
        when (val result = api.patchThread(agentId, threadId, RenameThreadBody(pinned = pinned))) {
            is ApiResult.Ok -> {
                threadDao.setPinned(threadId, pinned)
                result
            }

            is ApiResult.Failure -> result
        }

    /** 改名（`PATCH title`）。UI 在标题空白时就禁用确认，所以这里收到的一定是非空串。 */
    suspend fun rename(threadId: String, title: String): ApiResult<Unit> {
        val trimmed = title.trim()
        return when (val result = api.patchThread(agentId, threadId, RenameThreadBody(title = trimmed))) {
            is ApiResult.Ok -> {
                threadDao.setTitle(threadId, trimmed)
                result
            }

            is ApiResult.Failure -> result
        }
    }

    /** 改当前会话模型（`PATCH model_ref`）。 */
    suspend fun setModel(threadId: String, modelRef: String?): ApiResult<Unit> =
        when (val result = api.patchThread(agentId, threadId, RenameThreadBody(modelRef = modelRef))) {
            is ApiResult.Ok -> {
                threadDao.setModelRef(threadId, modelRef)
                result
            }

            is ApiResult.Failure -> result
        }

    /** 拉当前账号可见的模型列表，供移动端输入区选择。 */
    suspend fun resolvedModels(): ApiResult<List<ResolvedModelDto>> = api.resolvedModels()

    /** 删除会话（`DELETE`，204）。服务端删成功后本地一并删掉 —— 列表里就不该再有它。 */
    suspend fun delete(threadId: String): ApiResult<Unit> =
        when (val result = api.deleteThread(agentId, threadId)) {
            is ApiResult.Ok -> {
                threadDao.delete(threadId)
                result
            }

            is ApiResult.Failure -> result
        }

    /**
     * 打开会话即已读（docs/spec.md §11）：本地清零，并把服务端那份也清掉。
     *
     * 服务端的每个会话都有自己的未读计数，但列表接口不下发它，客户端只能自己攒
     * （见 [refresh]）；不顺手清服务端的话，下次刷新会把旧值再翻出来。
     * 服务端那次失败不影响界面 —— 返回结论以本地为准，所以没有返回值。
     */
    suspend fun markRead(threadId: String) {
        threadDao.markRead(threadId)
        api.markThreadRead(agentId, threadId)
    }
}

/**
 * 服务端时间戳是 epoch **秒**，本地统一毫秒（docs/spec.md §8.2）。
 *
 * `last_active` 缺失时退到 `created_at` —— 排序键不能是 0，否则会话会全部堆到列表底部。
 * 列表接口不返回最后一条消息，所以 [ThreadEntity.lastMessage] 沿用本地那份、由会话页回填。
 *
 * **未读数**（docs/protocol.md §5.2）：服务端只在「有消息落进这个会话」时才推 `last_active`，
 * 所以「比本地新」就是一个可靠的变化信号，据此 +1；[local] 为空的新会话从 0 起算。
 */
private fun ThreadDto.toEntity(agentId: String, local: ThreadEntity?): ThreadEntity {
    val activeSeconds = lastActive ?: createdAt ?: 0L
    val activeAt = activeSeconds * 1000
    val unread = when {
        local == null -> 0
        activeAt > local.lastActiveAt -> (local.unreadCount + 1).coerceAtMost(UNREAD_CAP)
        else -> local.unreadCount
    }
    return ThreadEntity(
        threadId = threadId,
        agentId = agentId,
        title = title?.takeIf { it.isNotBlank() },
        lastMessage = local?.lastMessage,
        pinned = pinned,
        lastActiveAt = activeAt,
        createdAt = (createdAt ?: activeSeconds) * 1000,
        unreadCount = unread,
        modelRef = modelRef,
        conversationMode = conversationMode,
        turnActive = turnActive,
        awaitingUser = awaitingUser,
    )
}

/** 角标显示上限，防止 `unreadCount` 无限累加（也避免徽标被撑成一条）。 */
private const val UNREAD_CAP = 99
