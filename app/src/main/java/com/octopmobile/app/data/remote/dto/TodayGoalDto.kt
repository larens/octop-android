package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TodayGoalDto(
    @SerialName("goal_id") val goalId: String,
    val title: String,
    val definition: String,
    @SerialName("success_rules") val successRules: String,
    val source: String,
    val status: String,
    @SerialName("review_at") val reviewAt: Long? = null,
    @SerialName("last_confirmed_at") val lastConfirmedAt: Long,
    @SerialName("needs_review") val needsReview: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class TodayGoalCreateBody(
    val title: String,
    val definition: String,
    @SerialName("success_rules") val successRules: String,
    @SerialName("review_at") val reviewAt: Long? = null,
)

@Serializable
data class TodayGoalPatchBody(
    val status: String? = null,
    val confirm: Boolean = false,
    @SerialName("review_at") val reviewAt: Long? = null,
)
