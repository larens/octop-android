package com.octopmobile.app.data.mobile

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.octopmobile.app.data.db.MobileSourceType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class XiaomiHealthReadResult(
    val events: List<MobileEventDraft>,
    val systemStepsReadable: Boolean,
    val systemStepRows: Int,
    val privateProbeSummary: String,
    val warnings: List<String> = emptyList(),
)

internal data class XiaomiStepSample(
    val beginAtMillis: Long,
    val steps: Long,
)

/**
 * Reads the MIUI system step provider directly. Xiaomi Health's private health-data
 * provider is probed without persisting any sample values; it is restricted to
 * system, Xiaomi-signed, or allow-listed packages on production devices.
 */
class XiaomiHealthSourceAdapter(private val contentResolver: ContentResolver) {
    suspend fun readRecent(now: Instant = Instant.now()): XiaomiHealthReadResult = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.ofInstant(now, zone)
        val firstDate = today.minusDays(29)
        val rangeStart = firstDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val timezoneOffsetMinutes = zone.rules.getOffset(now).totalSeconds / 60
        val warnings = mutableListOf<String>()

        val stepResult = runCatching { querySystemSteps(rangeStart, rangeEnd) }
            .getOrElse { error ->
                warnings += "系统步数读取失败：${error.userMessage()}"
                SystemStepResult(readable = false, samples = emptyList())
            }
        val dailySteps = aggregateXiaomiSteps(stepResult.samples, zone)
        val events = dailySteps.map { (date, steps) ->
            MobileEventDraft(
                id = "xiaomi-system-steps-$date-$steps",
                sourceType = MobileSourceType.XIAMI_HEALTH,
                sourceRecordId = date.toString(),
                eventType = "health.steps_30d",
                occurredAt = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                title = "小米运动健康 · 系统步数",
                summary = "$date · $steps 步",
                payloadJson = buildJsonObject {
                    put("date", date.toString())
                    put("steps", steps)
                    put("data_source", "xiaomi_system_steps")
                    put("data_origins", "com.mi.health")
                    put("timezone_offset_minutes", timezoneOffsetMinutes)
                }.toString(),
                privacyClass = SENSITIVE_HEALTH,
            )
        }.sortedBy { it.occurredAt }

        val probeResults = probePrivateProvider()
        probeResults.forEach { probe ->
            Log.i(TAG, "private health provider ${probe.path}: ${probe.status}")
        }
        val probeSummary = summarizeProbes(probeResults)
        val withTrigger = if (events.isEmpty()) {
            events
        } else {
            val reminderWindow = when (now.atZone(zone).hour) {
                in 5..11 -> "morning"
                in 12..17 -> "afternoon"
                in 18..23 -> "evening"
                else -> null
            }
            if (reminderWindow == null) {
                events
            } else {
                events + MobileEventDraft(
                    id = "xiaomi-health-sync-$today-$reminderWindow",
                    sourceType = MobileSourceType.XIAMI_HEALTH,
                    sourceRecordId = "$today-$reminderWindow",
                    eventType = "health.sync_window",
                    occurredAt = now.toEpochMilli(),
                    title = "小米运动健康 · 系统步数",
                    summary = "健康数据同步检查 · $reminderWindow",
                    payloadJson = buildJsonObject {
                        put("date", today.toString())
                        put("window", reminderWindow)
                        put("data_source", "xiaomi_system_steps")
                        put("timezone_offset_minutes", timezoneOffsetMinutes)
                    }.toString(),
                    privacyClass = SENSITIVE_HEALTH,
                )
            }
        }
        XiaomiHealthReadResult(
            events = withTrigger,
            systemStepsReadable = stepResult.readable,
            systemStepRows = stepResult.samples.size,
            privateProbeSummary = probeSummary,
            warnings = warnings,
        )
    }

    private fun querySystemSteps(rangeStart: Long, rangeEnd: Long): SystemStepResult {
        val selection = "$BEGIN_TIME>=$rangeStart AND $BEGIN_TIME<$rangeEnd"
        val uris = buildList {
            add(Uri.parse(SYSTEM_STEPS_URI))
            val latestSupported = runCatching {
                contentResolver.getType(Uri.parse(SYSTEM_STEPS_LATEST_URI)).isNullOrBlank().not()
            }.getOrDefault(false)
            if (latestSupported) add(Uri.parse(SYSTEM_STEPS_LATEST_URI))
        }
        var readable = false
        var lastError: Throwable? = null
        for (uri in uris) {
            try {
                val samples = contentResolver.query(
                    uri,
                    STEP_PROJECTION,
                    selection,
                    null,
                    "_id ASC",
                )?.use { cursor ->
                    readable = true
                    val beginIndex = cursor.getColumnIndexOrThrow(BEGIN_TIME)
                    val stepsIndex = cursor.getColumnIndexOrThrow(STEPS)
                    buildList {
                        while (cursor.moveToNext()) {
                            val steps = cursor.getLong(stepsIndex)
                            if (steps <= 0) continue
                            add(
                                XiaomiStepSample(
                                    beginAtMillis = normalizeEpochMillis(cursor.getLong(beginIndex)),
                                    steps = steps,
                                ),
                            )
                        }
                    }
                }.orEmpty()
                if (samples.isNotEmpty() || uri == uris.first()) {
                    return SystemStepResult(readable = readable, samples = samples)
                }
            } catch (error: Exception) {
                lastError = error
                if (error is SecurityException) break
            }
        }
        lastError?.let { throw it }
        return SystemStepResult(readable = readable, samples = emptyList())
    }

    private fun probePrivateProvider(): List<PrivateProbe> = PRIVATE_PROBES.map { path ->
        val uri = Uri.parse("$PRIVATE_AUTHORITY/$path")
        try {
            val type = runCatching { contentResolver.getType(uri) }.getOrNull()
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val columns = cursor.columnNames.take(12).joinToString(",")
                PrivateProbe(
                    path = path,
                    status = "readable(rows=${cursor.count},columns=$columns)",
                    readable = true,
                )
            } ?: PrivateProbe(path, "null_cursor(type=${type ?: "-"})", readable = false)
        } catch (error: SecurityException) {
            PrivateProbe(path, "security_denied", readable = false)
        } catch (error: IllegalArgumentException) {
            PrivateProbe(path, "not_registered(${error.userMessage()})", readable = false)
        } catch (error: Exception) {
            PrivateProbe(path, "error(${error.userMessage()})", readable = false)
        }
    }

    private fun summarizeProbes(probes: List<PrivateProbe>): String {
        val readable = probes.filter { it.readable }
        if (readable.isNotEmpty()) {
            return "小米私有接口可读：${readable.joinToString("、") { it.path }}"
        }
        val denied = probes.count { it.status == "security_denied" }
        return when {
            denied == probes.size -> "小米私有健康接口全部安全拒绝"
            denied > 0 -> "小米私有健康接口部分安全拒绝（$denied/${probes.size}）"
            else -> "小米私有健康接口未注册或不可用"
        }
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private data class SystemStepResult(
        val readable: Boolean,
        val samples: List<XiaomiStepSample>,
    )

    private data class PrivateProbe(
        val path: String,
        val status: String,
        val readable: Boolean,
    )

    private companion object {
        const val TAG = "XiaomiHealthSource"
        const val SENSITIVE_HEALTH = "sensitive_health"
        const val SYSTEM_STEPS_URI = "content://0@com.miui.providers.steps/item"
        const val SYSTEM_STEPS_LATEST_URI = "content://0@com.miui.providers.steps/item_latest"
        const val PRIVATE_AUTHORITY = "content://com.mi.health.provider.main"
        const val BEGIN_TIME = "_begin_time"
        const val STEPS = "_steps"
        val STEP_PROJECTION = arrayOf(BEGIN_TIME, "_end_time", "_mode", STEPS)
        val PRIVATE_PROBES = listOf(
            "widget/steps/simple",
            "widget/stand/simple",
            "activity/steps/brief",
            "heartrate/recent",
            "sleep/report",
        )
    }
}

internal fun aggregateXiaomiSteps(
    samples: List<XiaomiStepSample>,
    zone: ZoneId,
): Map<LocalDate, Long> = samples.asSequence()
    .filter { it.steps > 0 }
    .groupingBy { Instant.ofEpochMilli(it.beginAtMillis).atZone(zone).toLocalDate() }
    .fold(0L) { total, sample -> total + sample.steps }

private fun normalizeEpochMillis(value: Long): Long =
    if (value in 1..99_999_999_999L) value * 1_000 else value
