package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MobileIngestEventDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_record_id") val sourceRecordId: String? = null,
    @SerialName("event_type") val eventType: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("observed_at") val observedAt: Long,
    val payload: JsonElement,
    @SerialName("privacy_class") val privacyClass: String,
)

@Serializable
data class MobileIngestBatchBody(
    @SerialName("device_id") val deviceId: String,
    val events: List<MobileIngestEventDto>,
)

@Serializable
data class MobileIngestBatchResponse(
    @SerialName("accepted_event_ids") val acceptedEventIds: List<String> = emptyList(),
    @SerialName("rejected_event_ids") val rejectedEventIds: List<String> = emptyList(),
)
