package com.octopmobile.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 0 主会话镜像。服务端仍是真值来源，这里只缓存当前用户在一个 agent 下的稳定入口。
 *
 * 本阶段不替换现有 `threads` / `ChatRepository`，因此旧聊天路径仍可独立工作。
 */
@Entity(
    tableName = "main_conversations",
    indices = [Index(value = ["agentId", "userId"], unique = true)],
)
data class MainConversationEntity(
    @PrimaryKey val conversationId: String,
    val agentId: String,
    val userId: Long,
    val routerThreadId: String?,
    val stateJson: String,
    val summary: String,
    val lastConversationSeq: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
)

object ConversationTaskStatus {
    const val OPEN = "open"
    const val IN_PROGRESS = "in_progress"
    const val NEEDS_USER = "needs_user"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"
}

@Entity(
    tableName = "conversation_tasks",
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "status"]),
        Index(value = ["conversationId", "subjectKey"]),
    ],
)
data class ConversationTaskEntity(
    @PrimaryKey val taskId: String,
    val conversationId: String,
    val taskType: String,
    val title: String,
    val subjectKey: String,
    val objective: String,
    val status: String,
    val requiresUser: Boolean,
    val capsuleJson: String,
    val priority: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
)

@Entity(
    tableName = "conversation_events",
    indices = [
        Index("conversationId"),
        Index("traceId"),
        Index("taskId"),
        Index(value = ["conversationId", "seq"], unique = true),
    ],
)
data class ConversationEventEntity(
    @PrimaryKey val eventId: String,
    val conversationId: String,
    val traceId: String,
    val taskId: String?,
    val runId: String?,
    val parentEventId: String?,
    val seq: Long,
    val schemaVersion: Int,
    val eventType: String,
    val status: String?,
    val summary: String,
    val payloadRef: String?,
    val payloadJson: String?,
    val usageJson: String,
    val createdAt: Long,
)
