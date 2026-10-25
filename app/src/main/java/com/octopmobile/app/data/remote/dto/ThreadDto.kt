package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/agents/{agent_id}/threads` 的列表项。该接口返回**裸数组**
 * （上游 `chat/history.py:126-152`），不是 `{"items": [...]}`。
 */
@Serializable
data class ThreadDto(
    @SerialName("thread_id") val threadId: String,
    val title: String? = null,
    @SerialName("channel_type") val channelType: String? = null,
    @SerialName("session_key") val sessionKey: String? = null,
    /** epoch 秒。列表排序用的主键。 */
    @SerialName("last_active") val lastActive: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    /** 是否为该 agent 当前绑定的 dashboard 会话。 */
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("has_messages") val hasMessages: Boolean = false,
    /** 上游是 0/1 整型布尔，注意不要当成时间戳。 */
    val pinned: Boolean = false,
    @SerialName("model_ref") val modelRef: String? = null,
    @SerialName("reasoning_mode") val reasoningMode: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** `ask` / `plan` / `craft`；服务端缺省填 `craft`。 */
    @SerialName("conversation_mode") val conversationMode: String = ConversationMode.CRAFT,
    @SerialName("pending_plan_path") val pendingPlanPath: String? = null,
    @SerialName("hitl_policy") val hitlPolicy: HitlPolicyDto? = null,
    /** 回合仍在流式 —— 列表上「正在跑」状态点的依据。 */
    @SerialName("turn_active") val turnActive: Boolean = false,
    /** 卡在 HITL 等人工确认。 */
    @SerialName("awaiting_user") val awaitingUser: Boolean = false,
)

object ConversationMode {
    /** 只读。这是「只能问、不能派活」的根因，见 docs/protocol.md §4.1。 */
    const val ASK = "ask"

    /** 可写 `plans/` 目录下的 md 文件。 */
    const val PLAN = "plan"

    /** 完整读写，服务端默认。 */
    const val CRAFT = "craft"
}

/** `hitl_policy`：`{"mode": "ask"|"allow_all"|"allow_tools", "tools": [...]}`。 */
@Serializable
data class HitlPolicyDto(
    val mode: String? = null,
    val tools: List<String> = emptyList(),
)

/**
 * `POST /api/agents/{agent_id}/threads` 的响应（`chat/history.py:225-242`）。
 *
 * 副作用要说清：该调用是 `ThreadRegistry.reset()` —— **新建 thread 并把 dashboard 会话重绑上去**，
 * 旧会话不删但不再是绑定目标，同一账号的其他客户端（网页版）会被切到新会话。
 */
@Serializable
data class CreateThreadResponse(
    @SerialName("thread_id") val threadId: String,
    @SerialName("session_key") val sessionKey: String? = null,
)

/**
 * `GET /api/agents/{agent_id}/threads/{thread_id}/history` 的响应
 * （上游 `chat/history.py:391-411`）。
 */
@Serializable
data class HistoryResponse(
    @SerialName("thread_id") val threadId: String,
    val messages: List<MessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val limit: Int? = null,
    val offset: Int? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    /**
     * ⚠️ 为 true 时 [messages] 一定是**空数组** —— 服务端正在做历史投影。
     * 必须按 [historyRetryAfterMs] 轮询重试，否则老会话会显示成空白
     * （docs/protocol.md §5.2）。
     */
    @SerialName("history_loading") val historyLoading: Boolean = false,
    @SerialName("history_status") val historyStatus: String? = null,
    @SerialName("history_retry_after_ms") val historyRetryAfterMs: Long = 0,
    @SerialName("turn_active") val turnActive: Boolean = false,
    /** 待确认的 HITL 请求体，结构随工具而变，保留原始 JSON。 */
    @SerialName("hitl_pending") val hitlPending: JsonObject? = null,
    @SerialName("conversation_mode") val conversationMode: String = ConversationMode.CRAFT,
    @SerialName("pending_plan_path") val pendingPlanPath: String? = null,
    @SerialName("hitl_policy") val hitlPolicy: HitlPolicyDto? = null,
)
