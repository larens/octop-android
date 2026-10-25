package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /api/agents/{agent_id}/upload` 的响应（`routers/uploads.py:42-56`）。
 *
 * multipart 字段名固定是 **`file`**。发送时只用到 [workspacePath] / [filename] / [mediaType]
 * 三个字段，放进 `user_turn.messages` 的 content block（docs/protocol.md §6）。
 */
@Serializable
data class UploadResponse(
    val path: String? = null,
    @SerialName("workspace_path") val workspacePath: String? = null,
    val url: String? = null,
    @SerialName("access_url") val accessUrl: String? = null,
    val filename: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
)

/**
 * `POST /api/agents/{agent_id}/chat/hitl/resume` 的请求体（`chat/models.py:204-223`）。
 *
 * 响应是 **SSE**，不是 JSON —— 走 [com.octopmobile.app.data.remote.OctopApi.hitlResume]。
 */
@Serializable
data class HitlResumeBody(
    @SerialName("thread_id") val threadId: String,
    val decisions: List<HitlDecision>,
    @SerialName("pending_id") val pendingId: String? = null,
    @SerialName("hitl_policy") val hitlPolicy: HitlPolicyDto? = null,
)

/**
 * 单条人工决定。[type] 取值 `approve` / `reject` / `respond` / `edit`
 * （`chat/models.py` `_DECISION_TYPES`）。[message] 只有 `reject` / `respond` 用得上，
 * 会被原样注入模型上下文，因此服务端会校验非空。
 */
@Serializable
data class HitlDecision(
    val type: String,
    val message: String? = null,
) {
    companion object {
        const val APPROVE = "approve"
        const val REJECT = "reject"
        const val RESPOND = "respond"
    }
}
