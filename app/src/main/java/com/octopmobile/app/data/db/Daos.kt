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

    @Upsert
    suspend fun upsert(entity: ThreadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ThreadEntity>)

    @Query("UPDATE threads SET pinned = :pinned WHERE threadId = :threadId")
    suspend fun setPinned(threadId: String, pinned: Boolean)

    @Query("UPDATE threads SET unreadCount = 0 WHERE threadId = :threadId")
    suspend fun markRead(threadId: String)

    @Query("DELETE FROM threads WHERE threadId = :threadId")
    suspend fun delete(threadId: String)

    @Query("DELETE FROM threads WHERE agentId = :agentId")
    suspend fun deleteByAgent(agentId: String)

    @Query("DELETE FROM threads")
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

    @Query(
        """
        UPDATE messages
        SET status = :status,
            streamInterruptCount = streamInterruptCount + 1,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun markInterrupted(id: String, status: String, updatedAt: Long)

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

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE threadId = :threadId")
    fun observeByThread(threadId: String): Flow<List<AttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    @Query("UPDATE attachments SET localPath = :localPath, state = :state WHERE id = :id")
    suspend fun markCached(id: String, localPath: String, state: String)

    @Query("SELECT * FROM attachments WHERE state = 'cached' ORDER BY size DESC")
    suspend fun cachedBySizeDesc(): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)

    @Query("DELETE FROM attachments")
    suspend fun clear()
}

@Dao
interface CompactMarkerDao {

    @Query("SELECT * FROM compact_markers WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun observeByThread(threadId: String): Flow<List<CompactMarkerEntity>>

    @Upsert
    suspend fun upsert(entity: CompactMarkerEntity)

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

    @Query("DELETE FROM pending_ops")
    suspend fun clear()
}
