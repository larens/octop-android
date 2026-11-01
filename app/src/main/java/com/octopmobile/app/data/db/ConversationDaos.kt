package com.octopmobile.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MainConversationDao {
    @Query("SELECT * FROM main_conversations WHERE agentId = :agentId LIMIT 1")
    fun observeByAgent(agentId: String): Flow<MainConversationEntity?>

    @Query("SELECT * FROM main_conversations WHERE agentId = :agentId LIMIT 1")
    suspend fun find(agentId: String): MainConversationEntity?

    @Upsert
    suspend fun upsert(entity: MainConversationEntity)

    @Query("DELETE FROM main_conversations")
    suspend fun clear()
}

@Dao
interface ConversationTaskDao {
    @Query(
        """
        SELECT * FROM conversation_tasks
        WHERE conversationId = :conversationId
        ORDER BY priority DESC, updatedAt DESC
        """,
    )
    fun observeByConversation(conversationId: String): Flow<List<ConversationTaskEntity>>

    @Query("SELECT * FROM conversation_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun find(taskId: String): ConversationTaskEntity?

    @Upsert
    suspend fun upsert(entity: ConversationTaskEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ConversationTaskEntity>)

    @Query("DELETE FROM conversation_tasks")
    suspend fun clear()
}

@Dao
interface ConversationEventDao {
    @Query(
        """
        SELECT * FROM conversation_events
        WHERE conversationId = :conversationId AND seq > :afterSeq
        ORDER BY seq ASC
        LIMIT :limit
        """,
    )
    fun observeAfter(
        conversationId: String,
        afterSeq: Long,
        limit: Int,
    ): Flow<List<ConversationEventEntity>>

    @Query("SELECT MAX(seq) FROM conversation_events WHERE conversationId = :conversationId")
    suspend fun maxSeq(conversationId: String): Long?

    @Upsert
    suspend fun upsertAll(entities: List<ConversationEventEntity>)

    @Query("DELETE FROM conversation_events")
    suspend fun clear()
}
