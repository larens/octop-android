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
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_item_id") val planItemId: String? = null,
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
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_item_id") val planItemId: String? = null,
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
data class DispatchPlanItemDto(
    @SerialName("plan_item_id") val planItemId: String,
    val key: String,
    @SerialName("child_task_id") val childTaskId: String? = null,
    @SerialName("child_task_status") val childTaskStatus: String? = null,
    val title: String,
    val objective: String,
    @SerialName("worker_role") val workerRole: String = "research",
    @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
    val status: String,
    @SerialName("estimated_tokens") val estimatedTokens: Long = 0,
    @SerialName("token_limit") val tokenLimit: Long = 0,
    @SerialName("estimated_cost_micros") val estimatedCostMicros: Long = 0,
    @SerialName("cost_limit_micros") val costLimitMicros: Long = 0,
    @SerialName("actual_tokens") val actualTokens: Long = 0,
    @SerialName("actual_cost_micros") val actualCostMicros: Long = 0,
    @SerialName("attempt_count") val attemptCount: Int = 0,
    @SerialName("last_progress_at") val lastProgressAt: Long? = null,
    @SerialName("result_summary") val resultSummary: String = "",
)

@Serializable
data class DispatchPlanBudgetPreviewDto(
    @SerialName("max_concurrency") val maxConcurrency: Int = 1,
    @SerialName("max_attempts_per_worker") val maxAttemptsPerWorker: Int = 3,
    @SerialName("user_remaining_tokens") val userRemainingTokens: Long = 0,
    @SerialName("agent_remaining_tokens") val agentRemainingTokens: Long = 0,
    @SerialName("global_remaining_tokens") val globalRemainingTokens: Long = 0,
    @SerialName("worker_surface_remaining_tokens") val workerSurfaceRemainingTokens: Long = 0,
)

@Serializable
data class DispatchPlanBudgetRequestDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("plan_item_id") val planItemId: String,
    @SerialName("item_key") val itemKey: String,
    val title: String,
    val status: String,
    val reason: String,
    @SerialName("used_tokens") val usedTokens: Long = 0,
    @SerialName("old_token_limit") val oldTokenLimit: Long = 0,
    @SerialName("proposed_token_limit") val proposedTokenLimit: Long = 0,
    @SerialName("requested_tokens") val requestedTokens: Long = 0,
    @SerialName("requested_cost_micros") val requestedCostMicros: Long = 0,
    @SerialName("cost_per_1k_tokens_micros") val costPer1kTokensMicros: Long = 0,
    @SerialName("remaining_user_tokens") val remainingUserTokens: Long = 0,
    @SerialName("remaining_agent_tokens") val remainingAgentTokens: Long = 0,
    @SerialName("remaining_global_tokens") val remainingGlobalTokens: Long = 0,
    @SerialName("remaining_worker_surface_tokens") val remainingWorkerSurfaceTokens: Long = 0,
    @SerialName("can_continue") val canContinue: Boolean = false,
    @SerialName("max_extensions") val maxExtensions: Int = 1,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class DispatchPlanDto(
    @SerialName("plan_id") val planId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("context_pack_id") val contextPackId: String? = null,
    val objective: String,
    val status: String,
    val version: Int,
    @SerialName("plan_hash") val planHash: String,
    @SerialName("estimated_tokens") val estimatedTokens: Long,
    @SerialName("hard_token_limit") val hardTokenLimit: Long,
    @SerialName("soft_cost_micros") val softCostMicros: Long = 0,
    @SerialName("hard_cost_limit_micros") val hardCostLimitMicros: Long = 0,
    @SerialName("actual_tokens") val actualTokens: Long = 0,
    @SerialName("actual_cost_micros") val actualCostMicros: Long = 0,
    @SerialName("max_concurrency") val maxConcurrency: Int = 1,
    @SerialName("budget_extension_count") val budgetExtensionCount: Int = 0,
    @SerialName("budget_extended_tokens") val budgetExtendedTokens: Long = 0,
    @SerialName("budget_request") val budgetRequest: DispatchPlanBudgetRequestDto? = null,
    @SerialName("result_summary") val resultSummary: String = "",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("proposed_at") val proposedAt: Long,
    @SerialName("approved_at") val approvedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("budget_preview") val budgetPreview: DispatchPlanBudgetPreviewDto? = null,
    val items: List<DispatchPlanItemDto> = emptyList(),
)

@Serializable
data class DispatchPlanDecisionBody(
    @SerialName("plan_version") val planVersion: Int,
    @SerialName("plan_hash") val planHash: String,
)

@Serializable
data class DispatchPlanBudgetContinueBody(
    @SerialName("plan_version") val planVersion: Int,
    @SerialName("plan_hash") val planHash: String,
    @SerialName("budget_request_id") val budgetRequestId: String,
)

@Serializable
data class TaskApprovalDto(
    @SerialName("approval_id") val approvalId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("trace_id") val traceId: String,
    @SerialName("attempt_no") val attemptNo: Int? = null,
    @SerialName("task_title") val taskTitle: String,
    @SerialName("task_objective") val taskObjective: String,
    @SerialName("task_status") val taskStatus: String,
    @SerialName("action_name") val actionName: String,
    val action: JsonObject = JsonObject(emptyMap()),
    @SerialName("action_hash") val actionHash: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val status: String,
    val version: Int = 1,
    @SerialName("token_limit") val tokenLimit: Long = 0,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("execution_expires_at") val executionExpiresAt: Long? = null,
    @SerialName("decided_at") val decidedAt: Long? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class TaskApprovalDecisionDto(
    val approval: TaskApprovalDto,
)

@Serializable
data class TaskApprovalDecisionBody(
    @SerialName("action_hash") val actionHash: String,
    val version: Int,
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
    @SerialName("omitted_count") val omittedCount: Int = 0,
    @SerialName("omitted_by_reason") val omittedByReason: JsonObject = JsonObject(emptyMap()),
    @SerialName("omitted_refs_truncated") val omittedRefsTruncated: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class ConversationTraceDto(
    @SerialName("trace_id") val traceId: String,
    val events: List<ConversationEventDto>,
    val tasks: List<ConversationTaskDto> = emptyList(),
    @SerialName("context_packs") val contextPacks: List<ContextPackManifestDto> = emptyList(),
)
