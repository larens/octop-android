package com.octopmobile.app.data.mobile

data class MobileEventDraft(
    val id: String,
    val sourceType: String,
    val sourceRecordId: String?,
    val eventType: String,
    val occurredAt: Long,
    val title: String,
    val summary: String,
    val payloadJson: String,
    val privacyClass: String,
)
