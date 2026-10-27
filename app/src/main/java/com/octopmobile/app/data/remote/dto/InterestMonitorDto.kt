package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object InterestMonitorSchedule {
    const val MANUAL = "manual"
    const val MORNING = "morning"
    const val EVENING = "evening"
    const val TWICE_DAILY = "twice_daily"
}

@Serializable
data class InterestMonitorDto(
    @SerialName("monitor_id") val monitorId: String,
    val description: String,
    val schedule: String,
    val enabled: Boolean,
    @SerialName("baseline_established") val baselineEstablished: Boolean,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_result_count") val lastResultCount: Int = 0,
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("delivery_backoff_until") val deliveryBackoffUntil: Long? = null,
    @SerialName("feedback_backoff_reason") val feedbackBackoffReason: String? = null,
    @SerialName("feedback_backoff_level") val feedbackBackoffLevel: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("goal_id") val goalId: String? = null,
    @SerialName("goal_title") val goalTitle: String? = null,
    @SerialName("goal_needs_review") val goalNeedsReview: Boolean = false,
)

@Serializable
data class InterestMonitorCreateBody(
    val description: String,
    val schedule: String,
    @SerialName("goal_id") val goalId: String? = null,
)

@Serializable
data class InterestMonitorPatchBody(
    val enabled: Boolean? = null,
    val schedule: String? = null,
)

@Serializable
data class InterestMonitorRunResponse(
    val monitor: InterestMonitorDto,
    @SerialName("result_count") val resultCount: Int,
    @SerialName("new_count") val newCount: Int,
    @SerialName("published_count") val publishedCount: Int,
)
