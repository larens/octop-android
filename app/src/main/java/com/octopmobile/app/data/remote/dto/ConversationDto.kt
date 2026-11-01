package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Phase 0 稳定主会话；时间戳均为服务端 epoch 秒。 */
@Serializable
data class MainConversationDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("user_id") val userId: Long,
    @SerialName("router_thread_id") val routerThreadId: String? = null,
    val state: JsonObject = JsonObject(emptyMap()),
    val summary: String = "",
    @SerialName("last_conversation_seq") val lastConversationSeq: Long = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("archived_at") val archivedAt: Long? = null,
)

@Serializable
data class TaskRunDto(
    @SerialName("run_id") val runId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("worker_thread_id") val workerThreadId: String? = null,
    val status: String,
    @SerialName("attempt_no") val attemptNo: Int,
    @SerialName("failure_class") val failureClass: String? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("last_progress_at") val lastProgressAt: Long? = null,
    @SerialName("lease_until") val leaseUntil: Long? = null,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class ConversationTaskDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("task_type") val taskType: String,
    val title: String,
    @SerialName("subject_key") val subjectKey: String,
    val objective: String,
    val status: String,
    @SerialName("requires_user") val requiresUser: Boolean,
    val capsule: JsonObject = JsonObject(emptyMap()),
    val priority: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val runs: List<TaskRunDto> = emptyList(),
)

@Serializable
data class ConversationEventDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("parent_event_id") val parentEventId: String? = null,
    val seq: Long,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("event_type") val eventType: String,
    val status: String? = null,
    val summary: String = "",
    @SerialName("payload_ref") val payloadRef: String? = null,
    val payload: JsonElement? = null,
    val usage: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class ContextPackManifestDto(
    @SerialName("pack_id") val packId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("policy_version") val policyVersion: String,
    val model: String = "",
    @SerialName("used_tokens") val usedTokens: Long = 0,
    val sections: JsonArray = JsonArray(emptyList()),
    @SerialName("included_refs") val includedRefs: JsonArray = JsonArray(emptyList()),
    @SerialName("omitted_refs") val omittedRefs: JsonArray = JsonArray(emptyList()),
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class ConversationTraceDto(
    @SerialName("trace_id") val traceId: String,
    val events: List<ConversationEventDto>,
    val tasks: List<ConversationTaskDto> = emptyList(),
    @SerialName("context_packs") val contextPacks: List<ContextPackManifestDto> = emptyList(),
)
