package com.octopmobile.app.data.mobile

import androidx.room.withTransaction
import com.octopmobile.app.data.db.MobileEventEntity
import com.octopmobile.app.data.db.MobileOutboxEntity
import com.octopmobile.app.data.db.MobileSourceEntity
import com.octopmobile.app.data.db.MobileSourceType
import com.octopmobile.app.data.db.MobileUploadState
import com.octopmobile.app.data.db.OctopDatabase
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.MobileIngestBatchBody
import com.octopmobile.app.data.remote.dto.MobileIngestEventDto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MobileDataRepository(
    private val database: OctopDatabase,
    private val cipher: LocalPayloadCipher,
    private val json: Json,
) {
    fun observeSources(): Flow<List<MobileSourceEntity>> = database.mobileSources().observeAll()
    fun observeRecent(limit: Int = 20): Flow<List<MobileEventEntity>> = database.mobileEvents().observeRecent(limit)
    fun observePendingCount(): Flow<Int> = database.mobileEvents().observePendingCount()

    fun agentPrompt(event: MobileEventEntity): String = mobileSignalPrompt(
        eventType = event.eventType,
        summary = event.summary,
        payloadJson = cipher.decrypt(event.payloadJson),
        json = json,
    )

    suspend fun ensureSources() {
        val now = System.currentTimeMillis()
        listOf(
            MobileSourceType.SHARE_INBOX,
            MobileSourceType.SYSTEM_CALENDAR,
            MobileSourceType.HEALTH_CONNECT,
            MobileSourceType.XIAMI_HEALTH,
        )
            .forEach { source ->
                val existing = database.mobileSources().find(source)
                if (
                    source == MobileSourceType.HEALTH_CONNECT ||
                    source == MobileSourceType.XIAMI_HEALTH
                ) {
                    database.mobileSources().upsert(
                        MobileSourceEntity(
                            sourceType = source,
                            enabled = false,
                            permissionState = "server_managed",
                            lastSyncAt = existing?.lastSyncAt,
                            lastError = null,
                            updatedAt = now,
                        ),
                    )
                } else if (existing == null) {
                    database.mobileSources().upsert(
                        MobileSourceEntity(
                            sourceType = source,
                            enabled = source == MobileSourceType.SHARE_INBOX,
                            permissionState = if (source == MobileSourceType.SHARE_INBOX) "not_required" else "not_granted",
                            lastSyncAt = null,
                            lastError = null,
                            updatedAt = now,
                        ),
                    )
                }
            }
    }

    suspend fun setSourceState(sourceType: String, enabled: Boolean, permissionState: String, error: String? = null) {
        val old = database.mobileSources().find(sourceType)
        database.mobileSources().upsert(
            MobileSourceEntity(
                sourceType = sourceType,
                enabled = enabled,
                permissionState = permissionState,
                lastSyncAt = old?.lastSyncAt,
                lastError = error,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun disableSource(sourceType: String) {
        val old = database.mobileSources().find(sourceType) ?: return
        database.mobileSources().upsert(old.copy(enabled = false, updatedAt = System.currentTimeMillis()))
    }

    suspend fun collect(sourceType: String, block: suspend () -> List<MobileEventDraft>): Int {
        val source = database.mobileSources().find(sourceType) ?: return 0
        if (!source.enabled) return 0
        return runCatching {
            val drafts = block()
            val inserted = persist(drafts)
            database.mobileSources().upsert(
                source.copy(lastSyncAt = System.currentTimeMillis(), lastError = null, updatedAt = System.currentTimeMillis()),
            )
            inserted
        }.getOrElse { error ->
            database.mobileSources().upsert(
                source.copy(lastError = error.message ?: error.javaClass.simpleName, updatedAt = System.currentTimeMillis()),
            )
            throw error
        }
    }

    suspend fun saveShare(draft: MobileEventDraft): String {
        require(draft.sourceType == MobileSourceType.SHARE_INBOX)
        persist(listOf(draft))
        return draft.id
    }

    suspend fun deleteEvent(id: String) {
        database.withTransaction {
            database.mobileOutbox().delete(id)
            database.mobileEvents().delete(id)
        }
    }

    suspend fun uploadPending(api: OctopApi, agentId: String, deviceId: String): ApiResult<Int> {
        val outbox = database.mobileOutbox().next(50)
        if (outbox.isEmpty()) return ApiResult.Ok(0)
        val ids = outbox.map { it.eventId }
        database.mobileOutbox().markAttempted(ids, System.currentTimeMillis())
        val body = MobileIngestBatchBody(
            deviceId = deviceId,
            events = outbox.map { row ->
                val payload = json.parseToJsonElement(cipher.decrypt(row.payloadJson)).jsonObject
                MobileIngestEventDto(
                    eventId = row.eventId,
                    sourceType = payload.getValue("source_type").jsonPrimitive.content,
                    sourceRecordId = payload["source_record_id"]?.jsonPrimitive?.content,
                    eventType = payload.getValue("event_type").jsonPrimitive.content,
                    occurredAt = payload.getValue("occurred_at").jsonPrimitive.content.toLong(),
                    observedAt = payload.getValue("observed_at").jsonPrimitive.content.toLong(),
                    payload = payload.getValue("payload"),
                    privacyClass = payload.getValue("privacy_class").jsonPrimitive.content,
                )
            },
        )
        return when (val result = api.ingestMobileEvents(agentId, body)) {
            is ApiResult.Failure -> result
            is ApiResult.Ok -> {
                val accepted = result.value.acceptedEventIds.toSet().intersect(ids.toSet()).toList()
                database.withTransaction {
                    if (accepted.isNotEmpty()) {
                        database.mobileEvents().markUploaded(accepted)
                        database.mobileOutbox().deleteAll(accepted)
                    }
                }
                ApiResult.Ok(accepted.size)
            }
        }
    }

    private suspend fun persist(drafts: List<MobileEventDraft>): Int {
        if (drafts.isEmpty()) return 0
        val observedAt = System.currentTimeMillis()
        val events = drafts.map { draft ->
            MobileEventEntity(
                id = draft.id,
                sourceType = draft.sourceType,
                sourceRecordId = draft.sourceRecordId,
                eventType = draft.eventType,
                occurredAt = draft.occurredAt,
                observedAt = observedAt,
                title = draft.title,
                summary = draft.summary,
                payloadJson = cipher.encrypt(draft.payloadJson),
                privacyClass = draft.privacyClass,
                uploadState = MobileUploadState.PENDING,
                createdAt = observedAt,
            )
        }
        val outbox = drafts.map { draft ->
            val envelope = buildJsonObject {
                put("source_type", draft.sourceType)
                draft.sourceRecordId?.let { put("source_record_id", it) }
                put("event_type", draft.eventType)
                put("occurred_at", draft.occurredAt)
                put("observed_at", observedAt)
                put("payload", json.parseToJsonElement(draft.payloadJson))
                put("privacy_class", draft.privacyClass)
            }
            MobileOutboxEntity(draft.id, cipher.encrypt(envelope.toString()), 0, null, observedAt)
        }
        return database.withTransaction {
            val inserted = database.mobileEvents().insertNew(events)
            val newOutbox = outbox.filterIndexed { index, _ -> inserted[index] != -1L }
            if (newOutbox.isNotEmpty()) database.mobileOutbox().upsertAll(newOutbox)
            newOutbox.size
        }
    }
}
