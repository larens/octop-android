package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 历史里的单条消息（上游 `chat/serialize.py:921-961`）。
 *
 * 两种形态：
 * - `user` / `assistant`：`role` + `id` + `content[]`（+ user 才有 `attachments`）
 * - `tool`：`role="tool"`，`content[]` 里是 `tool_result`，另带 `agent_id`
 */
@Serializable
data class MessageDto(
    val role: String,
    val id: String? = null,
    val content: List<ContentBlockDto> = emptyList(),
    /** epoch 秒。 */
    @SerialName("created_at") val createdAt: Long? = null,
    val attachments: List<JsonObject> = emptyList(),
    /** 仅 `role == "tool"` 时出现（多 agent 场景）。 */
    @SerialName("agent_id") val agentId: String? = null,
)

/**
 * 内容块。
 *
 * 这里刻意**不做 sealed class**：上游 block 的字段集随版本与工具而变（`image` / `file` /
 * `tool_use` 的字段各不相同且未文档化），一旦按类型继承建模，多一个未知字段就会让整条消息解析失败。
 * 折中做法是「联合字段 + 忽略未知键」，把类型判断留给渲染层。
 *
 * 已知类型（`serialize.py:858-961`、`docs/protocol.md` §5.3）：
 * `text` / `thinking` / `tool_use` / `tool_result` / `image` / `image_url` / `file`
 */
@Serializable
data class ContentBlockDto(
    val type: String,
    /** `text`。 */
    val text: String? = null,
    /** `thinking`（上游字段名就是 `thinking`，不是 `content`）。 */
    val thinking: String? = null,
    /** `tool_use` 的调用 id；`tool_result` 与之对应。 */
    val id: String? = null,
    val name: String? = null,
    val output: String? = null,
    val artifact: JsonObject? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("workspace_path") val workspacePath: String? = null,
    val filename: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    /** 图片块用的是 `mime_type` 而不是 `media_type`（上游 `attachment_hints.make_workspace_image_ref`）。 */
    @SerialName("mime_type") val mimeType: String? = null,
    val url: String? = null,
)

object BlockType {
    const val TEXT = "text"
    const val THINKING = "thinking"
    const val TOOL_USE = "tool_use"
    const val TOOL_RESULT = "tool_result"
    const val IMAGE = "image"
    const val IMAGE_URL = "image_url"
    const val FILE = "file"
}

/** `PATCH /api/agents/{agent_id}/threads/{thread_id}` 的可写字段（`chat/models.py:194-201`）。 */
@Serializable
data class RenameThreadBody(
    val title: String? = null,
    val pinned: Boolean? = null,
    @SerialName("model_ref") val modelRef: String? = null,
    @SerialName("reasoning_mode") val reasoningMode: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("conversation_mode") val conversationMode: String? = null,
    @SerialName("hitl_policy") val hitlPolicy: HitlPolicyDto? = null,
)
