package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/agents` 的列表项，**裸数组**（上游 `routers/agents.py:227-235` 返回 `list[dict]`）。
 *
 * 会话与消息关联用的是 **`agent_id`（字符串业务标识）**，不是 `id`（agents 表的整数自增主键）。
 * 这两个字段同时存在，很容易用错。
 */
@Serializable
data class AgentDto(
    /** agents 表的整数自增主键，仅用于排序/去重，**不要**拿它拼接口路径。 */
    val id: Long? = null,
    @SerialName("agent_id") val agentId: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("default_model") val defaultModel: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val color: String? = null,
    @SerialName("welcome_message") val welcomeMessage: String? = null,
    /** 运行状态，如 `unknown` / `running` / `stopped`。 */
    val state: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    /** `expert` / `team`。 */
    val kind: String? = null,
    @SerialName("is_owner") val isOwner: Boolean = false,
    @SerialName("owner_username") val ownerUsername: String? = null,
    @SerialName("is_shared") val isShared: Boolean = false,
)

/** `GET /api/providers/resolved`：当前用户可选的聊天模型。 */
@Serializable
data class ResolvedModelDto(
    @SerialName("provider_name") val providerName: String,
    val model: String,
    val name: String? = null,
    val reasoning: Boolean = false,
    @SerialName("context_window") val contextWindow: Long? = null,
)
