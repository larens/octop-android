package com.octopmobile.app.data.mobile

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.octopmobile.app.data.db.MobileSourceType
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HealthConnectSourceAdapter(private val client: HealthConnectClient) {
    suspend fun readRecent(
        grantedPermissions: Set<String>,
        now: Instant = Instant.now(),
    ): List<MobileEventDraft> {
        val start = now.minus(30, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, now)
        val localNow = LocalDateTime.ofInstant(now, ZoneId.systemDefault())
        val dailySteps = if (HealthPermission.getReadPermission(StepsRecord::class) in grantedPermissions) {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    setOf(StepsRecord.COUNT_TOTAL),
                    TimeRangeFilter.between(localNow.minusDays(30), localNow),
                    Period.ofDays(1),
                ),
            )
        } else {
            emptyList()
        }
        val sleep = if (HealthPermission.getReadPermission(SleepSessionRecord::class) in grantedPermissions) {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
        } else {
            emptyList()
        }
        val exercise = if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in grantedPermissions) {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
        } else {
            emptyList()
        }
        val zone = ZoneId.systemDefault()
        return buildList {
            dailySteps.forEach { bucket ->
                val steps = bucket.result[StepsRecord.COUNT_TOTAL]
                if (steps != null && bucket.result.dataOrigins.isNotEmpty()) add(
                    MobileEventDraft(
                        id = "health-steps-${bucket.startTime.toLocalDate()}-$steps",
                        sourceType = MobileSourceType.HEALTH_CONNECT,
                        sourceRecordId = bucket.startTime.toLocalDate().toString(),
                        eventType = "health.steps_30d",
                        occurredAt = bucket.startTime.atZone(zone).toInstant().toEpochMilli(),
                        title = "Health Connect",
                        summary = "${bucket.startTime.toLocalDate()} · $steps 步",
                        payloadJson = buildJsonObject {
                            put("steps", steps)
                            put("date", bucket.startTime.toLocalDate().toString())
                            put("data_origins", bucket.result.dataOrigins.joinToString(",") { it.packageName })
                        }.toString(),
                        privacyClass = "sensitive_health",
                    ),
                )
            }
            sleep.forEach { record ->
                add(
                    MobileEventDraft(
                        id = "health-sleep-${record.metadata.id}",
                        sourceType = MobileSourceType.HEALTH_CONNECT,
                        sourceRecordId = record.metadata.id,
                        eventType = "health.sleep_session",
                        occurredAt = record.startTime.toEpochMilli(),
                        title = "Health Connect",
                        summary = "睡眠记录 ${record.startTime.atZone(zone).toLocalDate()}",
                        payloadJson = buildJsonObject {
                            put("starts_at", record.startTime.toEpochMilli())
                            put("ends_at", record.endTime.toEpochMilli())
                            put("data_origin", record.metadata.dataOrigin.packageName)
                        }.toString(),
                        privacyClass = "sensitive_health",
                    ),
                )
            }
            exercise.forEach { record ->
                add(
                    MobileEventDraft(
                        id = "health-exercise-${record.metadata.id}",
                        sourceType = MobileSourceType.HEALTH_CONNECT,
                        sourceRecordId = record.metadata.id,
                        eventType = "health.exercise_session",
                        occurredAt = record.startTime.toEpochMilli(),
                        title = "Health Connect",
                        summary = "运动记录 ${record.startTime.atZone(zone).toLocalDate()}",
                        payloadJson = buildJsonObject {
                            put("starts_at", record.startTime.toEpochMilli())
                            put("ends_at", record.endTime.toEpochMilli())
                            put("exercise_type", record.exerciseType)
                            put("data_origin", record.metadata.dataOrigin.packageName)
                        }.toString(),
                        privacyClass = "sensitive_health",
                    ),
                )
            }
        }
    }
}
