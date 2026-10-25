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

/** 会话标签。Today 化之后会话不只是一串消息，还要能挂到项目 / 主题下面。 */
object ThreadTagKind {
    const val PROJECT = "project"
    const val TOPIC = "topic"

    /** 界面上的可选项顺序，与 docs/spec.md §23.3 的「必须显式区分两类」对应。 */
    val ALL = listOf(PROJECT, TOPIC)
}

@Entity(
    tableName = "thread_tags",
    primaryKeys = ["threadId", "tag"],
    indices = [Index("tag"), Index("kind")],
)
data class ThreadTagEntity(
    val threadId: String,
    val tag: String,
    /** [ThreadTagKind] 之一。 */
    val kind: String,
    val createdAt: Long,
)

/** 长期记忆条目。这里只放可审计、可编辑的结构化摘要，不直接把整段聊天塞进来。 */
object MemoryKind {
    const val PERSON = "person"
    const val PROJECT = "project"
    const val PREFERENCE = "preference"
    const val COMMITMENT = "commitment"

    /** 首批只支持这四类（docs/spec.md §23.3），界面上的可选顺序即此。 */
    val ALL = listOf(PERSON, PROJECT, PREFERENCE, COMMITMENT)
}

@Entity(
    tableName = "memory_items",
    indices = [Index("kind"), Index("sourceThreadId"), Index("updatedAt")],
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    /** [MemoryKind] 之一。 */
    val kind: String,
    val title: String,
    val summary: String,
    val sourceThreadId: String?,
    val sourceMessageId: String?,
    val updatedAt: Long,
)

/**
 * 自动任务。**真值在服务端**（`/api/agents/{id}/cron`）：服务端才是真正按点执行的那一方，
 * 本地这张表只是镜像，供列表离线可读（docs/protocol.md §5.5）。
 */
object AutomationTaskStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
}

@Entity(
    tableName = "automation_tasks",
    indices = [Index("agentId"), Index("status"), Index("nextRunAt")],
)
data class AutomationTaskEntity(
    /** 服务端 cron 的 `cron_id`，本地直接沿用，省掉一层映射。 */
    @PrimaryKey val id: String,
    val agentId: String,
    /** 服务端 `name`。 */
    val title: String,
    val prompt: String,
    /** 服务端 `trigger` 原文：`cron:0 9 * * *` / `interval:3600` / `date:…` / `agently:…`。 */
    val scheduleRule: String,
    /** 服务端 `session_key`；为 null 表示任务自己另起会话。 */
    val scopeRef: String?,
    /** [AutomationTaskStatus] 之一，由服务端 `enabled` 映射而来。 */
    val status: String,
    /** 服务端 `last_run_at`（epoch 秒 → 毫秒）。 */
    val lastRunAt: Long?,
    /** 服务端 `last_status`：`ok` / `error`，从未跑过为 null。 */
    val lastStatus: String?,
    /** 服务端 `last_error`，失败时的原因摘要。 */
    val lastError: String?,
    /** 本地按 [scheduleRule] 推算出来的下次执行时间，只用于展示。 */
    val nextRunAt: Long?,
    /** 服务端没有创建时间字段，本地首次见到它时记一次。 */
    val createdAt: Long,
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

/** 附件缓存状态。 */
object AttachmentState {
    /** 只知道远端地址，本地还没有文件。 */
    const val REMOTE_ONLY = "remote_only"
    const val CACHED = "cached"
    const val FAILED = "failed"
}

/** 附件缓存索引。二进制落在 `filesDir/attachments/<threadId>/`，本表只记引用与状态。 */
@Entity(tableName = "attachments", indices = [Index("threadId"), Index("messageId")])
data class AttachmentEntity(
    /** [com.octopmobile.app.data.attachments.AttachmentRefs.idOf] 算出的引用哈希，也用作落盘文件名。 */
    @PrimaryKey val id: String,
    val threadId: String,
    val messageId: String?,
    /** **归一化后的相对引用**（不含主机名），见 `data/attachments/AttachmentRefs.kt`。 */
    val remoteUrl: String,
    val localPath: String?,
    val mime: String?,
    val size: Long,
    /** [AttachmentState] 之一。 */
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
