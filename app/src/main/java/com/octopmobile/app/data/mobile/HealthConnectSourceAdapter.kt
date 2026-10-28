package com.octopmobile.app.data.mobile

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.octopmobile.app.data.db.MobileSourceType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HealthConnectSourceAdapter(private val client: HealthConnectClient) {
    suspend fun readRecent(
        grantedPermissions: Set<String>,
        now: Instant = Instant.now(),
    ): List<MobileEventDraft> {
        val zone = ZoneId.systemDefault()
        val start = now.minus(30, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, now)
        val localNow = LocalDateTime.ofInstant(now, zone)
        val aggregateRange = TimeRangeFilter.between(
            localNow.toLocalDate().minusDays(30).atStartOfDay(),
            localNow,
        )
        val timezoneOffsetMinutes = zone.rules.getOffset(now).totalSeconds / 60

        val dailySteps = aggregate(
            grantedPermissions,
            StepsRecord::class,
            setOf(StepsRecord.COUNT_TOTAL),
            aggregateRange,
        )
        val dailyHeartRate = aggregate(
            grantedPermissions,
            HeartRateRecord::class,
            setOf(
                HeartRateRecord.BPM_AVG,
                HeartRateRecord.BPM_MIN,
                HeartRateRecord.BPM_MAX,
                HeartRateRecord.MEASUREMENTS_COUNT,
            ),
            aggregateRange,
        )
        val dailyRestingHeartRate = aggregate(
            grantedPermissions,
            RestingHeartRateRecord::class,
            setOf(
                RestingHeartRateRecord.BPM_AVG,
                RestingHeartRateRecord.BPM_MIN,
                RestingHeartRateRecord.BPM_MAX,
            ),
            aggregateRange,
        )
        val dailyCalories = aggregate(
            grantedPermissions,
            ActiveCaloriesBurnedRecord::class,
            setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            aggregateRange,
        )
        val dailyDistance = aggregate(
            grantedPermissions,
            DistanceRecord::class,
            setOf(DistanceRecord.DISTANCE_TOTAL),
            aggregateRange,
        )
        val dailyFloors = aggregate(
            grantedPermissions,
            FloorsClimbedRecord::class,
            setOf(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
            aggregateRange,
        )

        val sleep = records<SleepSessionRecord>(grantedPermissions, range)
        val exercise = records<ExerciseSessionRecord>(grantedPermissions, range)
        val oxygen = records<OxygenSaturationRecord>(grantedPermissions, range)
        val hrv = records<HeartRateVariabilityRmssdRecord>(grantedPermissions, range)

        val events = buildList {
            dailySteps.forEach { bucket ->
                val steps = bucket.result[StepsRecord.COUNT_TOTAL]
                if (steps != null && bucket.result.dataOrigins.isNotEmpty()) {
                    val date = bucket.startTime.toLocalDate()
                    add(
                        MobileEventDraft(
                            id = "health-steps-$date-$steps",
                            sourceType = MobileSourceType.HEALTH_CONNECT,
                            sourceRecordId = date.toString(),
                            eventType = "health.steps_30d",
                            occurredAt = startOfDay(date, zone),
                            title = "Health Connect",
                            summary = "$date · $steps 步",
                            payloadJson = buildJsonObject {
                                put("steps", steps)
                                put("date", date.toString())
                                put("data_origins", originNames(bucket.result.dataOrigins))
                                put("timezone_offset_minutes", timezoneOffsetMinutes)
                            }.toString(),
                            privacyClass = SENSITIVE_HEALTH,
                        ),
                    )
                }
            }

            dailyHeartRate.forEach { bucket ->
                val average = bucket.result[HeartRateRecord.BPM_AVG]
                val count = bucket.result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0
                if (average != null && count > 0 && bucket.result.dataOrigins.isNotEmpty()) {
                    val date = bucket.startTime.toLocalDate()
                    add(
                        MobileEventDraft(
                            id = "health-heart-$date-$count-$average",
                            sourceType = MobileSourceType.HEALTH_CONNECT,
                            sourceRecordId = date.toString(),
                            eventType = "health.heart_rate_daily",
                            occurredAt = startOfDay(date, zone),
                            title = "Health Connect",
                            summary = "$date · 平均心率 $average bpm",
                            payloadJson = buildJsonObject {
                                put("date", date.toString())
                                put("average_bpm", average)
                                put("min_bpm", bucket.result[HeartRateRecord.BPM_MIN])
                                put("max_bpm", bucket.result[HeartRateRecord.BPM_MAX])
                                put("measurements", count)
                                put("data_origins", originNames(bucket.result.dataOrigins))
                                put("timezone_offset_minutes", timezoneOffsetMinutes)
                            }.toString(),
                            privacyClass = SENSITIVE_HEALTH,
                        ),
                    )
                }
            }

            dailyRestingHeartRate.forEach { bucket ->
                val average = bucket.result[RestingHeartRateRecord.BPM_AVG]
                if (average != null && bucket.result.dataOrigins.isNotEmpty()) {
                    val date = bucket.startTime.toLocalDate()
                    add(
                        MobileEventDraft(
                            id = "health-resting-heart-$date-$average",
                            sourceType = MobileSourceType.HEALTH_CONNECT,
                            sourceRecordId = date.toString(),
                            eventType = "health.resting_heart_rate_daily",
                            occurredAt = startOfDay(date, zone),
                            title = "Health Connect",
                            summary = "$date · 静息心率 $average bpm",
                            payloadJson = buildJsonObject {
                                put("date", date.toString())
                                put("average_bpm", average)
                                put("min_bpm", bucket.result[RestingHeartRateRecord.BPM_MIN])
                                put("max_bpm", bucket.result[RestingHeartRateRecord.BPM_MAX])
                                put("data_origins", originNames(bucket.result.dataOrigins))
                                put("timezone_offset_minutes", timezoneOffsetMinutes)
                            }.toString(),
                            privacyClass = SENSITIVE_HEALTH,
                        ),
                    )
                }
            }

            dailyCalories.forEach { bucket ->
                val calories = bucket.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                if (calories != null && bucket.result.dataOrigins.isNotEmpty()) {
                    val date = bucket.startTime.toLocalDate()
                    val value = calories.inKilocalories.roundToInt()
                    add(
                        dailyMetricDraft(
                            id = "health-calories-$date-$value",
                            eventType = "health.active_calories_daily",
                            date = date,
                            zone = zone,
                            summary = "$date · 活动热量 $value 千卡",
                            payload = buildJsonObject {
                                put("date", date.toString())
                                put("active_calories_kcal", value)
                                put("data_origins", originNames(bucket.result.dataOrigins))
                                put("timezone_offset_minutes", timezoneOffsetMinutes)
                            }.toString(),
                        ),
                    )
                }
            }

            dailyDistance.forEach { bucket ->
                val distance = bucket.result[DistanceRecord.DISTANCE_TOTAL]
                if (distance != null && bucket.result.dataOrigins.isNotEmpty()) {
                    val date = bucket.startTime.toLocalDate()
                    val meters = distance.inMeters.roundToInt()
                    add(
                        dailyMetricDraft(
                            id = "health-distance-$date-$meters",
                            eventType = "health.distance_daily",
                            date = date,
                            zone = zone,
                            summary = "$date · 距离 $meters 米",
                            payload = buildJsonObject {
                                put("date", date.toString())
                                put("distance_meters", meters)
                                put("data_origins", originNames(bucket.result.dataOrigins))
                                put("timezone_offset_minutes", timezoneOffsetMinutes)
                            }.toString(),
                        ),
                    )
                }
            }

            dailyFloors.forEach { bucket ->
                val floors = bucket.result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
                if (floors != null && bucket.result.dataOrigins.isNotEmpty()) {
                    val date = bucket.startTime.toLocalDate()
                    val value = floors.roundToInt()
                    add(
                        dailyMetricDraft(
                            id = "health-floors-$date-$value",
                            eventType = "health.floors_daily",
                            date = date,
                            zone = zone,
                            summary = "$date · 爬楼 $value 层",
                            payload = buildJsonObject {
                                put("date", date.toString())
                                put("floors", value)
                                put("data_origins", originNames(bucket.result.dataOrigins))
                                put("timezone_offset_minutes", timezoneOffsetMinutes)
                            }.toString(),
                        ),
                    )
                }
            }

            oxygen.distinctBy { it.time to it.percentage.value }
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .forEach { (date, samples) ->
                val values = samples.map { it.percentage.value }
                val average = values.average()
                add(
                    dailyMetricDraft(
                        id = "health-oxygen-$date-${values.size}-${average.roundToInt()}",
                        eventType = "health.oxygen_saturation_daily",
                        date = date,
                        zone = zone,
                        occurredAt = samples.maxOf { it.time }.toEpochMilli(),
                        summary = "$date · 血氧 ${average.roundToInt()}%",
                        payload = buildJsonObject {
                            put("date", date.toString())
                            put("average_percent", average)
                            put("min_percent", values.min())
                            put("max_percent", values.max())
                            put("measurements", values.size)
                            put("data_origins", samples.map { it.metadata.dataOrigin.packageName }.distinct().joinToString(","))
                            put("timezone_offset_minutes", timezoneOffsetMinutes)
                        }.toString(),
                    ),
                )
            }

            hrv.distinctBy { it.time to it.heartRateVariabilityMillis }
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .forEach { (date, samples) ->
                val values = samples.map { it.heartRateVariabilityMillis }
                val average = values.average()
                add(
                    dailyMetricDraft(
                        id = "health-hrv-$date-${values.size}-${average.roundToInt()}",
                        eventType = "health.hrv_daily",
                        date = date,
                        zone = zone,
                        occurredAt = samples.maxOf { it.time }.toEpochMilli(),
                        summary = "$date · HRV ${average.roundToInt()} ms",
                        payload = buildJsonObject {
                            put("date", date.toString())
                            put("average_ms", average)
                            put("min_ms", values.min())
                            put("max_ms", values.max())
                            put("measurements", values.size)
                            put("data_origins", samples.map { it.metadata.dataOrigin.packageName }.distinct().joinToString(","))
                            put("timezone_offset_minutes", timezoneOffsetMinutes)
                        }.toString(),
                    ),
                )
            }

            sleep.distinctBy { it.startTime to it.endTime }.forEach { record ->
                val stages = stageMinutes(record)
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
                            put("duration_minutes", ChronoUnit.MINUTES.between(record.startTime, record.endTime))
                            put("stages_minutes", buildJsonObject {
                                stages.forEach { (name, minutes) -> put(name, minutes) }
                            })
                            put("data_origin", record.metadata.dataOrigin.packageName)
                            put("timezone_offset_minutes", timezoneOffsetMinutes)
                        }.toString(),
                        privacyClass = SENSITIVE_HEALTH,
                    ),
                )
            }

            exercise.distinctBy { Triple(it.startTime, it.endTime, it.exerciseType) }.forEach { record ->
                val durationMinutes = ChronoUnit.MINUTES.between(record.startTime, record.endTime)
                val typeName = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[record.exerciseType]
                    ?: "other_workout"
                add(
                    MobileEventDraft(
                        id = "health-exercise-${record.metadata.id}",
                        sourceType = MobileSourceType.HEALTH_CONNECT,
                        sourceRecordId = record.metadata.id,
                        eventType = "health.exercise_session",
                        occurredAt = record.startTime.toEpochMilli(),
                        title = "Health Connect",
                        summary = "$typeName · $durationMinutes 分钟",
                        payloadJson = buildJsonObject {
                            put("starts_at", record.startTime.toEpochMilli())
                            put("ends_at", record.endTime.toEpochMilli())
                            put("duration_minutes", durationMinutes)
                            put("exercise_type", record.exerciseType)
                            put("exercise_type_name", typeName)
                            put("data_origin", record.metadata.dataOrigin.packageName)
                            put("timezone_offset_minutes", timezoneOffsetMinutes)
                        }.toString(),
                        privacyClass = SENSITIVE_HEALTH,
                    ),
                )
            }
        }
        val reminderWindow = when (localNow.hour) {
            in 5..11 -> "morning"
            in 18..23 -> "evening"
            else -> null
        }
        if (reminderWindow != null && events.isNotEmpty()) {
            return events + MobileEventDraft(
                id = "health-sync-${localNow.toLocalDate()}-$reminderWindow",
                sourceType = MobileSourceType.HEALTH_CONNECT,
                sourceRecordId = "${localNow.toLocalDate()}-$reminderWindow",
                eventType = "health.sync_window",
                occurredAt = now.toEpochMilli(),
                title = "Health Connect",
                summary = "健康数据同步检查 · $reminderWindow",
                payloadJson = buildJsonObject {
                    put("date", localNow.toLocalDate().toString())
                    put("window", reminderWindow)
                    put("timezone_offset_minutes", timezoneOffsetMinutes)
                }.toString(),
                privacyClass = SENSITIVE_HEALTH,
            )
        }
        return events
    }

    private suspend fun aggregate(
        grantedPermissions: Set<String>,
        recordType: KClass<out Record>,
        metrics: Set<AggregateMetric<*>>,
        range: TimeRangeFilter,
    ): List<AggregationResultGroupedByPeriod> {
        if (HealthPermission.getReadPermission(recordType) !in grantedPermissions) {
            return emptyList()
        }
        return client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics,
                range,
                Period.ofDays(1),
            ),
        )
    }

    private suspend inline fun <reified T : Record> records(
        grantedPermissions: Set<String>,
        range: TimeRangeFilter,
    ): List<T> {
        @Suppress("UNCHECKED_CAST")
        val recordType = when (T::class) {
            SleepSessionRecord::class -> SleepSessionRecord::class
            ExerciseSessionRecord::class -> ExerciseSessionRecord::class
            OxygenSaturationRecord::class -> OxygenSaturationRecord::class
            HeartRateVariabilityRmssdRecord::class -> HeartRateVariabilityRmssdRecord::class
            else -> return emptyList()
        } as KClass<T>
        if (HealthPermission.getReadPermission(recordType) !in grantedPermissions) {
            return emptyList()
        }
        return client.readRecords(ReadRecordsRequest(recordType, range)).records
    }

    private fun dailyMetricDraft(
        id: String,
        eventType: String,
        date: LocalDate,
        zone: ZoneId,
        summary: String,
        payload: String,
        occurredAt: Long = startOfDay(date, zone),
    ) = MobileEventDraft(
        id = id,
        sourceType = MobileSourceType.HEALTH_CONNECT,
        sourceRecordId = date.toString(),
        eventType = eventType,
        occurredAt = occurredAt,
        title = "Health Connect",
        summary = summary,
        payloadJson = payload,
        privacyClass = SENSITIVE_HEALTH,
    )

    private fun stageMinutes(record: SleepSessionRecord): Map<String, Long> {
        if (record.stages.isEmpty()) return emptyMap()
        val totals = linkedMapOf<String, Long>()
        record.stages.forEach { stage ->
            val key = when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                -> "awake"
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
                SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                else -> "unknown"
            }
            val minutes = ChronoUnit.MINUTES.between(stage.startTime, stage.endTime).coerceAtLeast(0)
            totals[key] = (totals[key] ?: 0) + minutes
        }
        return totals
    }

    private fun originNames(origins: Set<DataOrigin>): String =
        origins.map { it.packageName }.distinct().sorted().joinToString(",")

    private fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        const val SENSITIVE_HEALTH = "sensitive_health"
    }
}
