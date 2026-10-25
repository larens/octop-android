package com.octopmobile.app.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 客户端 → 服务端的帧（docs/protocol.md §4.1）。
 *
 * 这些帧是**我们自己定义**的，字段与上游 `UserTurnWsFrame` 对齐，属于完全确定的部分。
 * 序列化结果形如 `{"type":"user_turn","text":"..."}` ——
 * kotlinx-serialization 多态默认的判别键就是 `type`，正好与协议一致，无需额外配置。
 */
@Serializable
sealed interface ClientFrame {

    /** 心跳保活。空闲 30s 发一次（docs/spec.md §10）。 */
    @Serializable
    @SerialName("ping")
    data object Ping : ClientFrame

    /** 订阅某个线程的增量事件；服务端会先回一帧 `turn_status`。 */
    @Serializable
    @SerialName("subscribe")
    data class Subscribe(
        @SerialName("thread_id") val threadId: String,
    ) : ClientFrame

    /** 中断当前回合。 */
    @Serializable
    @SerialName("cancel")
    data class Cancel(
        @SerialName("thread_id") val threadId: String,
    ) : ClientFrame

    /** 撤回排队中的转向指令。 */
    @Serializable
    @SerialName("steer_remove")
    data class SteerRemove(
        @SerialName("thread_id") val threadId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("client_pending_id") val clientPendingId: String? = null,
        @SerialName("pending_id") val pendingId: String? = null,
    ) : ClientFrame

    /**
     * 发送一轮输入。
     *
     * [clientPendingId] 必须带 —— 服务端会在 `steer.*` 帧里原样回传，是把本地占位消息
     * 与服务端消息对上的唯一手段（protocol.md §8 第 7 条）。
     *
     * [expectedTurnId] 在「回合进行中再发送」时必填，否则被判定为 steer 拒绝。
     * 本版回合进行中输入框置灰，所以恒为空。
     *
     * [messages] 只在**带附件**时使用：上游用 content block 里的 `workspace_path` /
     * `filename` / `media_type` 识别附件（protocol.md §6）。纯文本时只发 [text] ——
     * 上游 `from_ws_payload` 会把非空 `text` 包成一条 user 消息（`chat/models.py:86-92`）。
     *
     * [targetAgentIds] 是 `@专家`（spec §23.5）唯一的服务端抓手：上游字段说明为
     * “Optional agent ids to involve via @mention (same user only)”
     * （`api/routers/chat/models.py:64-67`），服务端把它原样透传给本轮（`chat/turn.py:289`）。
     * 上游**没有**按回合切换角色 / 系统提示词的机制（`system_prompt` 是 agent 级配置），
     * 所以「切换专家」在协议层就等于「把另一个专家拉进这一轮」。
     */
    @Serializable
    @SerialName("user_turn")
    data class UserTurn(
        val text: String? = null,
        @SerialName("thread_id") val threadId: String? = null,
        @SerialName("session_key") val sessionKey: String? = null,
        val messages: List<UserMessage>? = null,
        val model: String? = null,
        @SerialName("target_agent_ids") val targetAgentIds: List<String>? = null,
        @SerialName("default_model") val defaultModel: String? = null,
        @SerialName("reasoning_mode") val reasoningMode: String? = null,
        @SerialName("reasoning_effort") val reasoningEffort: String? = null,
        @SerialName("conversation_mode") val conversationMode: String? = null,
        /** `auto` / `steer` / `follow_up`。空闲时用 `auto` 即正常发送。 */
        val delivery: String? = null,
        @SerialName("client_pending_id") val clientPendingId: String? = null,
        @SerialName("expected_turn_id") val expectedTurnId: String? = null,
    ) : ClientFrame
}

/** `user_turn.messages` 里的一条消息（OpenAI 风格）。 */
@Serializable
data class UserMessage(
    val role: String = ROLE_USER,
    val content: List<UserContentBlock> = emptyList(),
)

/**
 * 消息内容块。字段名与上游读取处对齐：附件走 `workspace_path` + `filename` + `media_type`
 * （`chat/ws.py:523-558`）。
 */
@Serializable
data class UserContentBlock(
    /** `text` / `image` / `file`。 */
    val type: String,
    val text: String? = null,
    @SerialName("workspace_path") val workspacePath: String? = null,
    val filename: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
) {
    companion object {
        const val TEXT = "text"
        const val IMAGE = "image"
        const val FILE = "file"
    }
}

private const val ROLE_USER = "user"
