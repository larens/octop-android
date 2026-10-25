package com.octopmobile.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 表结构，对应 docs/spec.md §8.2。
 *
 * 两条设计原则：
 * 1. **服务端是真值来源，本地库只是显示缓存** —— 任何一张表都可以整体重建，不做「本地改动待合并」。
 * 2. 时间戳统一用 **epoch 毫秒**；服务端给的是 epoch 秒，由 repo 层转换。
 */

/** 一台已连接的 Octop 实例。`capability` 值为 `native` 或 `webview_only`（§9.5 能力探测结论）。 */
@Entity(tableName = "instances")
data class InstanceEntity(
    @PrimaryKey val baseUrl: String,
    val displayName: String?,
    val capability: String,
    val lastConnectedAt: Long,
)

/**
 * 会话。排序统一 `pinned` 优先、其后 `lastActiveAt` 倒序（§8.3）。
 *
 * [turnActive] / [awaitingUser] 直接来自服务端列表接口的 `turn_active` / `awaiting_user`，
 * 是列表上「正在跑 / 等你确认」状态点的唯一依据。
 */
@Entity(tableName = "threads", indices = [Index("agentId")])
data class ThreadEntity(
    @PrimaryKey val threadId: String,
    val agentId: String,
    val title: String?,
    val lastMessage: String?,
    /** 服务端 `threads.pinned` 是 0/1 布尔值，**不是时间戳**。 */
    val pinned: Boolean,
    /** 对应服务端 `last_active`，列表排序用的主键。 */
    val lastActiveAt: Long,
    val createdAt: Long,
    val unreadCount: Int,
    val modelRef: String?,
    /** `ask` / `plan` / `craft`；见 docs/protocol.md §4.1。 */
    val conversationMode: String,
    val turnActive: Boolean,
    val awaitingUser: Boolean,
)

/** 消息状态。流式进行中的那行会被持续 UPDATE，因此杀进程重进也能看到半截输出（§8.1）。 */
object MessageStatus {
    const val PENDING = "pending"
    const val STREAMING = "streaming"
    const val DONE = "done"
    const val INTERRUPTED = "interrupted"
    const val FAILED = "failed"
}

/** 一条消息。正文存在 [partsJson]（与 WS 事件同构的 parts 数组），不拆表。 */
@Entity(
    tableName = "messages",
    indices = [Index("threadId"), Index("turnId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val turnId: String?,
    val role: String,
    val sortOrder: Long,
    val partsJson: String,
    val status: String,
    val errorInfo: String?,
    val streamInterruptCount: Int,
    val updatedAt: Long,
)

/** 上下文压缩点，UI 上画一条分隔线。 */
@Entity(tableName = "compact_markers", primaryKeys = ["threadId", "createdAt"])
data class CompactMarkerEntity(
    val threadId: String,
    val createdAt: Long,
    val summary: String,
)

/** 附件缓存索引。二进制落在 `filesDir/attachments/<threadId>/`，本表只记引用与状态。 */
@Entity(tableName = "attachments", indices = [Index("threadId"), Index("messageId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val messageId: String?,
    val remoteUrl: String,
    val localPath: String?,
    val mime: String?,
    val size: Long,
    /** `remote_only` / `cached` / `failed`。 */
    val state: String,
)

/** 断网时排队的操作，恢复后按序重放（§10）。 */
@Entity(tableName = "pending_ops")
data class PendingOpEntity(
    @PrimaryKey val id: String,
    /** `send` / `cancel` / `steer`。 */
    val kind: String,
    val threadId: String?,
    val payloadJson: String,
    val retryCount: Int,
    val createdAt: Long,
)
