package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 定时任务（服务端 cron，docs/protocol.md §5.5）。
 *
 * 字段清单来自上游 `CronJobRow.to_public_dict`
 * （`octop/infra/db/repos/cron.py:108-126`）+ `routers/cron.py` 的 `include_agent=True`。
 *
 * 客户端只做「镜像 + 增删改」，**真正按点执行的是服务端 APScheduler** ——
 * 这也是这里不需要 WorkManager/闹钟的原因。
 */
@Serializable
data class CronJobDto(
    /** 服务端 `cron_id`（ULID）。本地 [com.octopmobile.app.data.db.AutomationTaskEntity.id] 直接用它。 */
    val id: String,
    val name: String? = null,
    /**
     * 触发规则原文，四种形态：
     * `cron:0 9 * * *`、`interval:3600`（秒）、`date:2030-01-01T08:00:00`、`agently:INSTANCE_ID`（新邮件事件）。
     */
    val trigger: String = "",
    val prompt: String = "",
    @SerialName("session_key") val sessionKey: String? = null,
    val model: String? = null,
    /** true 表示每次执行都另起一个新会话，不复用 `session_key`。 */
    @SerialName("fresh_thread") val freshThread: Boolean = false,
    val enabled: Boolean = true,
    @SerialName("task_type") val taskType: String = "text",
    @SerialName("mcp_servers") val mcpServers: List<String> = emptyList(),
    /** epoch **秒**。 */
    @SerialName("last_run_at") val lastRunAt: Long? = null,
    /** `ok` / `error`；从未执行过为 null。 */
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("last_error") val lastError: String? = null,
)

/** `POST /api/agents/{agent_id}/cron` 的请求体（`CronCreateBody`）。`null` 字段不会进请求体。 */
@Serializable
data class CronJobCreateBody(
    val name: String? = null,
    val trigger: String,
    val prompt: String,
    @SerialName("session_key") val sessionKey: String? = null,
    @SerialName("fresh_thread") val freshThread: Boolean? = null,
    val enabled: Boolean? = null,
    val model: String? = null,
    @SerialName("task_type") val taskType: String? = null,
    /** 推荐任务显式绑定匹配的已安装连接器；手工任务为 null，继续沿用服务端默认。 */
    @SerialName("mcp_servers") val mcpServers: List<String>? = null,
)

/**
 * `PATCH /api/agents/{agent_id}/cron/{cron_id}` 的请求体（`CronPatchBody`）。
 *
 * 「停止 / 继续」就是把 [enabled] 翻一下；上游用 `exclude_unset` 判断「这个字段要不要改」，
 * 而 `Json.explicitNulls = false` 让 null 字段直接不出现在 JSON 里，正好对上。
 */
@Serializable
data class CronJobPatchBody(
    val name: String? = null,
    val trigger: String? = null,
    val prompt: String? = null,
    val enabled: Boolean? = null,
    @SerialName("mcp_servers") val mcpServers: List<String>? = null,
)
