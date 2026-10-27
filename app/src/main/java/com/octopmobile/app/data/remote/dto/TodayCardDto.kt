package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TodayEvidenceDto(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_record_id") val sourceRecordId: String? = null,
    @SerialName("event_type") val eventType: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("privacy_class") val privacyClass: String,
    val url: String? = null,
    val title: String? = null,
    val excerpt: String? = null,
)

@Serializable
data class TodayCardDto(
    @SerialName("card_id") val cardId: String,
    val kind: String,
    val title: String,
    val summary: String,
    @SerialName("why_now") val whyNow: String,
    val confidence: Double,
    @SerialName("risk_level") val riskLevel: String = "A2",
    val lifecycle: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("snoozed_until") val snoozedUntil: Long? = null,
    @SerialName("action_prompt") val actionPrompt: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val evidence: List<TodayEvidenceDto> = emptyList(),
    @SerialName("goal_id") val goalId: String? = null,
    @SerialName("goal_title") val goalTitle: String? = null,
    @SerialName("goal_needs_review") val goalNeedsReview: Boolean = false,
    @SerialName("execution_thread_id") val executionThreadId: String? = null,
    @SerialName("execution_turn_id") val executionTurnId: String? = null,
    @SerialName("action_state") val actionState: String? = null,
    @SerialName("action_error") val actionError: String? = null,
    @SerialName("action_updated_at") val actionUpdatedAt: Long? = null,
)

@Serializable
data class TodaySourceCoverageDto(
    @SerialName("source_type") val sourceType: String,
    val state: String,
    @SerialName("last_success_at") val lastSuccessAt: Long? = null,
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("issue_count") val issueCount: Int = 0,
)

@Serializable
data class TodayCoverageDto(
    @SerialName("generated_at") val generatedAt: Long,
    val sources: List<TodaySourceCoverageDto> = emptyList(),
)

@Serializable
data class TodayCardPatchBody(
    val lifecycle: String,
    @SerialName("snoozed_until") val snoozedUntil: Long? = null,
)

object TodayFeedbackType {
    const val USEFUL = "useful"
    const val IRRELEVANT = "irrelevant"
    const val TOO_FREQUENT = "too_frequent"
    const val POOR_SOURCE = "poor_source"
}

@Serializable
data class TodayCardFeedbackBody(
    @SerialName("feedback_type") val feedbackType: String,
)

object TodayCardActionState {
    const val PREPARED = "prepared"
    const val RUNNING = "running"
    const val WAITING_CONFIRMATION = "waiting_confirmation"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

@Serializable
data class TodayCardActionBody(
    val state: String,
    @SerialName("thread_id") val threadId: String? = null,
    @SerialName("turn_id") val turnId: String? = null,
    val error: String? = null,
)
