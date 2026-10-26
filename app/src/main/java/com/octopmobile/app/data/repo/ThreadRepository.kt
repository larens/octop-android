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
import java.util.UUID

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
                // Cron/task sessions are implementation details, not user conversations.
                val visible = result.value.filter(::isVisibleConversation)
                val entities = visible.map { it.toEntity(agentId, local[it.threadId]) }
                threadDao.upsertAll(entities)
                // 只有「这一页没满」才能确定是全量；满页说明服务端可能还有更多，不能动手。
                if (result.value.size < limit) {
                    // 本地草稿（draft-…）是「等首条消息才建的服务端会话」，不属于服务端已
                    // 不存在的孤儿 —— 收敛时必须留着，否则聊天页刚开的「新会话」会被整行删掉。
                    val drafts = local.values.filter { isDraftId(it.threadId) }.map { it.threadId }
                    val keepIds = visible.map { it.threadId } + drafts
                    if (keepIds.isEmpty()) {
                        threadDao.deleteAllByAgent(agentId)
                    } else {
                        threadDao.deleteMissing(agentId, keepIds)
                    }
                }
                ApiResult.Ok(entities.size)
            }

            is ApiResult.Failure -> result
        }

    /**
     * 新建**本地草稿**会话，直接返回草稿 id（不碰服务端）。
     *
     * 策略是「等第一条消息真正发出去时才在服务端建会话」（见 [createServerThread]）：
     * 冷启动 / 点「+」只是打开一个本地占位的「新会话」，用户不发消息就不会在服务端
     * 留下空的会话记录。草稿 id 带 [DRAFT_PREFIX]，聊天页据此知道要先绑定服务端 id。
     */
    suspend fun createLocalDraft(): String {
        // 同一时间只保留一条「新会话」草稿：进程被系统杀掉时 ChatScreen 的退出清理不会跑，
        // 旧草稿会一直留在本地；再开新会话时顺手清掉，抽屉里就不会堆一串无意义的「新会话」。
        threadDao.deleteDrafts(agentId, DRAFT_PREFIX)
        val id = DRAFT_PREFIX + UUID.randomUUID()
        val now = System.currentTimeMillis()
        threadDao.upsert(
            ThreadEntity(
                threadId = id,
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
        return id
    }

    /**
     * 在服务端创建真实会话并返回 `thread_id`（`ThreadRegistry.reset()`：新建一条 thread
     * 并把 dashboard 会话重绑过去，见 docs/protocol.md §5.2）。
     *
     * 只在**用户第一次发送消息**时调用（`ChatViewModel.send`）：拿到回复里的 id 后，
     * 聊天仓库把本地草稿整行迁移过去再发这一轮。这里不写本地库 —— 草稿行的身份迁移
     * 由 [com.octopmobile.app.data.repo.ChatRepository.bindServerThread] 负责。
     */
    suspend fun createServerThread(): ApiResult<String> =
        when (val result = api.createThread(agentId)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.threadId)
            is ApiResult.Failure -> result
        }

    companion object {
        /** 本地草稿会话的 id 前缀：这串 id 只存在于本机，服务端建好真实会话后被替换掉。 */
        const val DRAFT_PREFIX = "draft-"
        fun isDraftId(threadId: String): Boolean = threadId.startsWith(DRAFT_PREFIX)
    }

    /**
     * 置顶 / 取消置顶（`PATCH pinned`，docs/protocol.md §5.2）。
     *
     * 一律**服务端先成功、再写本地**：列表完全由 Room 驱动，写库那一刻界面就更新了，
     * 不需要在 UI 上另存一份「正在置顶」的状态；失败时界面保持原样，只提示。
     */
    suspend fun setPinned(threadId: String, pinned: Boolean): ApiResult<Unit> =
        if (isDraftId(threadId)) {
            // 草稿还没有服务端身份，先记在本地（服务端那份等发首条消息建好后自然会同步）。
            threadDao.setPinned(threadId, pinned)
            ApiResult.Ok(Unit)
        } else {
            when (val result = api.patchThread(agentId, threadId, RenameThreadBody(pinned = pinned))) {
                is ApiResult.Ok -> {
                    threadDao.setPinned(threadId, pinned)
                    result
                }

                is ApiResult.Failure -> result
            }
        }

    /** 改名（`PATCH title`）。UI 在标题空白时就禁用确认，所以这里收到的一定是非空串。 */
    suspend fun rename(threadId: String, title: String): ApiResult<Unit> {
        val trimmed = title.trim()
        return if (isDraftId(threadId)) {
            // 同 setPinned：只写本地，服务端会话还不存在。
            threadDao.setTitle(threadId, trimmed)
            ApiResult.Ok(Unit)
        } else {
            when (val result = api.patchThread(agentId, threadId, RenameThreadBody(title = trimmed))) {
                is ApiResult.Ok -> {
                    threadDao.setTitle(threadId, trimmed)
                    result
                }

                is ApiResult.Failure -> result
            }
        }
    }

    /** 改当前会话模型（`PATCH model_ref`）。 */
    suspend fun setModel(threadId: String, modelRef: String?): ApiResult<Unit> =
        if (isDraftId(threadId)) {
            threadDao.setModelRef(threadId, modelRef)
            ApiResult.Ok(Unit)
        } else {
            when (val result = api.patchThread(agentId, threadId, RenameThreadBody(modelRef = modelRef))) {
                is ApiResult.Ok -> {
                    threadDao.setModelRef(threadId, modelRef)
                    result
                }

                is ApiResult.Failure -> result
            }
        }

    /** 拉当前账号可见的模型列表，供移动端输入区选择。 */
    suspend fun resolvedModels(): ApiResult<List<ResolvedModelDto>> = api.resolvedModels()

    /**
     * 删除会话（`DELETE`，204）。服务端删成功后本地一并删掉 —— 列表里就不该再有它。
     * 草稿在服务端根本不存在，直接删本地行即可，不需要（也不会成功）发 DELETE。
     */
    suspend fun delete(threadId: String): ApiResult<Unit> =
        if (isDraftId(threadId)) {
            threadDao.delete(threadId)
            ApiResult.Ok(Unit)
        } else {
            when (val result = api.deleteThread(agentId, threadId)) {
                is ApiResult.Ok -> {
                    threadDao.delete(threadId)
                    result
                }

                is ApiResult.Failure -> result
            }
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
 * 服务端空会话的 `last_active` 是 `0`（列为 NOT NULL），不是「缺失」也不是有效时间戳；
 * 0 和缺失都退到 `created_at`，实在拿不到就沿用本地那份，绝不能让排序键变 0
 * （否则列表时间显示成 1970-01-01，会话也会堆到最底部）。
 * 列表接口不返回最后一条消息，所以 [ThreadEntity.lastMessage] 沿用本地那份、由会话页回填。
 *
 * **未读数**（docs/protocol.md §5.2）：服务端只在「有消息落进这个会话」时才推 `last_active`，
 * 所以「比本地新」就是一个可靠的变化信号，据此 +1；[local] 为空的新会话从 0 起算。
 */
private fun ThreadDto.toEntity(agentId: String, local: ThreadEntity?): ThreadEntity {
    val activeSeconds = lastActive?.takeIf { it > 0 }
        ?: createdAt?.takeIf { it > 0 }
        ?: local?.lastActiveAt?.div(1000)
        ?: 0L
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
        createdAt = createdAt?.takeIf { it > 0 }?.times(1000)
            ?: local?.createdAt
            ?: activeAt,
        unreadCount = unread,
        modelRef = modelRef,
        conversationMode = conversationMode,
        turnActive = turnActive,
        awaitingUser = awaitingUser,
    )
}

internal fun isVisibleConversation(thread: ThreadDto): Boolean =
    (thread.channelType == null || thread.channelType == "dashboard") &&
        thread.sessionKey?.contains(":dashboard:android-task-") != true

/** 角标显示上限，防止 `unreadCount` 无限累加（也避免徽标被撑成一条）。 */
private const val UNREAD_CAP = 99

