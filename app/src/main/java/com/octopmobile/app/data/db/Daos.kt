package com.octopmobile.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InstanceDao {

    @Query("SELECT * FROM instances ORDER BY lastConnectedAt DESC")
    fun observeAll(): Flow<List<InstanceEntity>>

    @Query("SELECT * FROM instances WHERE baseUrl = :baseUrl LIMIT 1")
    suspend fun find(baseUrl: String): InstanceEntity?

    @Upsert
    suspend fun upsert(entity: InstanceEntity)

    @Query("UPDATE instances SET capability = :capability WHERE baseUrl = :baseUrl")
    suspend fun setCapability(baseUrl: String, capability: String)

    @Query("DELETE FROM instances")
    suspend fun clear()
}

@Dao
interface ThreadDao {

    @Query(
        """
        SELECT * FROM threads
        WHERE agentId = :agentId
        ORDER BY pinned DESC, lastActiveAt DESC
        """,
    )
    fun observeByAgent(agentId: String): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE threadId = :threadId LIMIT 1")
    fun observeOne(threadId: String): Flow<ThreadEntity?>

    /** 一次性读全量，供列表刷新时和本地已有状态做合并（未读数与消息摘要不能被覆盖掉）。 */
    @Query("SELECT * FROM threads WHERE agentId = :agentId")
    suspend fun listByAgent(agentId: String): List<ThreadEntity>

    @Upsert
    suspend fun upsert(entity: ThreadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ThreadEntity>)

    @Query("DELETE FROM threads WHERE agentId = :agentId")
    suspend fun deleteAllByAgent(agentId: String)

    /** 清掉该 agent 下所有的本地草稿会话（id 带 [prefix]）。 */
    @Query("DELETE FROM threads WHERE agentId = :agentId AND threadId LIKE :prefix || '%'")
    suspend fun deleteDrafts(agentId: String, prefix: String)

    /**
     * 删掉服务端已不存在的本地行（全量同步后的收敛，见
     * [com.octopmobile.app.data.repo.ThreadRepository.refresh]）。
     * 空列表会让 `NOT IN ()` 变成非法 SQL，空集情况由调用方走 [deleteAllByAgent]。
     */
    @Query("DELETE FROM threads WHERE agentId = :agentId AND threadId NOT IN (:keepIds)")
    suspend fun deleteMissing(agentId: String, keepIds: List<String>)

    @Query("UPDATE threads SET pinned = :pinned WHERE threadId = :threadId")
    suspend fun setPinned(threadId: String, pinned: Boolean)

    @Query("UPDATE threads SET title = :title WHERE threadId = :threadId")
    suspend fun setTitle(threadId: String, title: String?)

    @Query("UPDATE threads SET modelRef = :modelRef WHERE threadId = :threadId")
    suspend fun setModelRef(threadId: String, modelRef: String?)

    @Query("UPDATE threads SET unreadCount = 0 WHERE threadId = :threadId")
    suspend fun markRead(threadId: String)

    @Query("DELETE FROM threads WHERE threadId = :threadId")
    suspend fun delete(threadId: String)

    /**
     * 草稿会话绑上服务端 id 时整行改名（见
     * [com.octopmobile.app.data.repo.ChatRepository.bindServerThread]）。主键可以直接
     * UPDATE：本地表之间没有外键约束，`threadId` 只是按值关联的一列。
     */
    @Query("UPDATE threads SET threadId = :newId WHERE threadId = :oldId")
    suspend fun moveThread(oldId: String, newId: String)

    @Query("DELETE FROM threads WHERE agentId = :agentId")
    suspend fun deleteByAgent(agentId: String)

    @Query("DELETE FROM threads")
    suspend fun clear()
}

@Dao
interface ThreadTagDao {

    @Query("SELECT * FROM thread_tags WHERE threadId = :threadId ORDER BY kind ASC, tag ASC")
    fun observeByThread(threadId: String): Flow<List<ThreadTagEntity>>

    @Query("SELECT * FROM thread_tags ORDER BY kind ASC, tag ASC")
    fun observeAll(): Flow<List<ThreadTagEntity>>

    @Upsert
    suspend fun upsert(entity: ThreadTagEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ThreadTagEntity>)

    @Query("DELETE FROM thread_tags WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)

    @Query("DELETE FROM thread_tags WHERE threadId = :threadId AND tag = :tag")
    suspend fun deleteOne(threadId: String, tag: String)

    @Query("DELETE FROM thread_tags")
    suspend fun clear()
}

@Dao
interface MemoryItemDao {

    @Query("SELECT * FROM memory_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE kind = :kind ORDER BY updatedAt DESC")
    fun observeByKind(kind: String): Flow<List<MemoryItemEntity>>

    /** 编辑时要拿原值做底（`updatedAt` 等）再改，不能凭空造一条。 */
    @Query("SELECT * FROM memory_items WHERE id = :id LIMIT 1")
    suspend fun find(id: String): MemoryItemEntity?

    @Upsert
    suspend fun upsert(entity: MemoryItemEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MemoryItemEntity>)

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memory_items")
    suspend fun clear()
}

@Dao
interface AutomationTaskDao {

    /** 服务端 cron 列表是**全量**接口，所以镜像也按 agent 整体替换（见 `AutomationRepository.refresh`）。 */
    @Query("SELECT * FROM automation_tasks WHERE agentId = :agentId ORDER BY nextRunAt ASC, createdAt DESC")
    fun observeByAgent(agentId: String): Flow<List<AutomationTaskEntity>>

    /**
     * 一次性读全量，供刷新时合并：服务端没有 `createdAt` 字段，本地首次见到某条任务时记一次，
     * 覆盖写不能把这个值冲掉。
     */
    @Query("SELECT * FROM automation_tasks WHERE agentId = :agentId")
    suspend fun listByAgent(agentId: String): List<AutomationTaskEntity>

    @Upsert
    suspend fun upsert(entity: AutomationTaskEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AutomationTaskEntity>)

    @Query("DELETE FROM automation_tasks WHERE agentId = :agentId AND id NOT IN (:keepIds)")
    suspend fun deleteMissing(agentId: String, keepIds: List<String>)

    @Query("DELETE FROM automation_tasks WHERE agentId = :agentId")
    suspend fun deleteByAgent(agentId: String)

    @Query("DELETE FROM automation_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM automation_tasks")
    suspend fun clear()
}

@Dao
interface MessageDao {

    /**
     * `id` 作为次级排序键是必要的：历史消息的 `sortOrder` 由服务端的 `created_at`（秒）推导，
     * 同一秒内的多条消息（助手正文 + 工具调用）会撞在一起，只靠 `sortOrder` 排序不稳定。
     */
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY sortOrder ASC, id ASC")
    fun observeByThread(threadId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun find(id: String): MessageEntity?

    @Query("SELECT MAX(sortOrder) FROM messages WHERE threadId = :threadId")
    suspend fun maxSortOrder(threadId: String): Long?

    @Upsert
    suspend fun upsert(entity: MessageEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MessageEntity>)

    @Query(
        """
        INSERT INTO messages
            (id, threadId, turnId, role, sortOrder, partsJson, status, errorInfo,
             streamInterruptCount, updatedAt)
        VALUES
            (:id, :threadId, :turnId, :role, :sortOrder, :partsJson, :status, NULL, 0, :updatedAt)
        ON CONFLICT(id) DO UPDATE SET
            turnId = excluded.turnId,
            partsJson = excluded.partsJson,
            status = excluded.status,
            updatedAt = excluded.updatedAt
        """,
    )
    suspend fun upsertStreaming(
        id: String,
        threadId: String,
        turnId: String?,
        role: String,
        sortOrder: Long,
        partsJson: String,
        status: String,
        updatedAt: Long,
    )

    /**
     * 把一条消息标成「输出中断」，并记一次中断次数（spec §10）。
     *
     * `partsJson` 一起写，是为了让「落最后一段正文」与「标记中断」在**同一条语句**里完成：
     * 合帧（[upsertStreaming] 的调用方）可能刚好吃掉最后一帧，分两步写就有机会留下一个
     * 状态是 interrupted、内容却少一截的行。
     */
    @Query(
        """
        UPDATE messages
        SET status = :status,
            partsJson = :partsJson,
            streamInterruptCount = streamInterruptCount + 1,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun markInterrupted(
        id: String,
        status: String,
        partsJson: String,
        updatedAt: Long,
    )

    /**
     * 进程被杀导致的「僵尸流式行」自愈：重新进页面时把本地还没收尾的行标成中断。
     *
     * 这类行是上个进程留下的 `streaming` / `pending`，本进程的 `live` 为 null，永远不会
     * 有人来收尾它 —— 界面上就会一直显示成正在输出。只扫本地行（[prefix]），
     * 服务端 id 的行不受影响。
     */
    @Query(
        """
        UPDATE messages
        SET status = :to,
            streamInterruptCount = streamInterruptCount + 1,
            updatedAt = :updatedAt
        WHERE threadId = :threadId AND status IN (:from) AND id LIKE :prefix || '%'
        """,
    )
    suspend fun markStaleInterrupted(
        threadId: String,
        from: List<String>,
        to: String,
        prefix: String,
        updatedAt: Long,
    )

    /**
     * 删除流式期间落下的本地占位行。
     *
     * 历史拉回来之后，服务端版本的消息（自带 `message_id`）与本地占位行（[prefix] 开头）
     * 指的是同一轮输出，留着就是两条重复气泡。正在流式的那些不受影响 —— 由 [status] 过滤。
     */
    @Query("DELETE FROM messages WHERE threadId = :threadId AND status = :status AND id LIKE :prefix || '%'")
    suspend fun deletePlaceholders(threadId: String, status: String, prefix: String)

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)

    @Query("UPDATE messages SET threadId = :newId WHERE threadId = :oldId")
    suspend fun moveThread(oldId: String, newId: String)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE threadId = :threadId")
    fun observeByThread(threadId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun find(id: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    /** 文件被容量控制淘汰后，索引要退回「只有远端还有」，否则 UI 会去找一个不存在的文件。 */
    @Query("UPDATE attachments SET localPath = NULL, state = 'remote_only' WHERE id = :id")
    suspend fun markEvicted(id: String)

    /** 下载失败。下次渲染时 UI 会再触发一次，所以这里是可恢复状态、不是终态。 */
    @Query("UPDATE attachments SET state = 'failed' WHERE id = :id")
    suspend fun markFailed(id: String)

    @Query("DELETE FROM attachments WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)

    @Query("UPDATE attachments SET threadId = :newId WHERE threadId = :oldId")
    suspend fun moveThread(oldId: String, newId: String)

    @Query("DELETE FROM attachments")
    suspend fun clear()
}

@Dao
interface CompactMarkerDao {

    @Query("SELECT * FROM compact_markers WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun observeByThread(threadId: String): Flow<List<CompactMarkerEntity>>

    @Upsert
    suspend fun upsert(entity: CompactMarkerEntity)

    @Query("UPDATE compact_markers SET threadId = :newId WHERE threadId = :oldId")
    suspend fun moveThread(oldId: String, newId: String)

    @Query("DELETE FROM compact_markers")
    suspend fun clear()
}

@Dao
interface PendingOpDao {

    @Query("SELECT * FROM pending_ops ORDER BY createdAt ASC")
    suspend fun all(): List<PendingOpEntity>

    @Query("SELECT * FROM pending_ops WHERE threadId IS :threadId ORDER BY createdAt ASC")
    fun observeByThread(threadId: String?): Flow<List<PendingOpEntity>>

    @Upsert
    suspend fun upsert(entity: PendingOpEntity)

    @Query("UPDATE pending_ops SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun bumpRetry(id: String)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pending_ops SET threadId = :newId WHERE threadId = :oldId")
    suspend fun moveThread(oldId: String, newId: String)

    @Query("DELETE FROM pending_ops")
    suspend fun clear()
}
