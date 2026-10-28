package com.octopmobile.app.data.mobile

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun mobileSignalPrompt(
    eventType: String,
    summary: String,
    payloadJson: String,
    json: Json = Json,
): String {
    val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
    val details = when (eventType) {
        "calendar.event" -> listOfNotNull(
            payload?.text("title"),
            payload?.long("starts_at")?.let { "开始时间：${formatTime(it)}" },
            payload?.long("ends_at")?.let { "结束时间：${formatTime(it)}" },
            payload?.text("location")?.let { "地点：$it" },
        )

        "health.steps_30d" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("steps")?.let { "步数：$it" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.sleep_session" -> listOfNotNull(
            payload?.long("starts_at")?.let { "开始时间：${formatTime(it)}" },
            payload?.long("ends_at")?.let { "结束时间：${formatTime(it)}" },
            payload?.long("duration_minutes")?.let { "睡眠时长：$it 分钟" },
            payload?.text("data_origin")?.let { "数据来源：$it" },
        )

        "health.exercise_session" -> listOfNotNull(
            payload?.long("starts_at")?.let { "开始时间：${formatTime(it)}" },
            payload?.long("ends_at")?.let { "结束时间：${formatTime(it)}" },
            payload?.text("exercise_type_name")?.let { "运动类型：$it" },
            payload?.long("duration_minutes")?.let { "运动时长：$it 分钟" },
            payload?.text("data_origin")?.let { "数据来源：$it" },
        )

        "health.heart_rate_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("average_bpm")?.let { "平均心率：$it bpm" },
            payload?.long("min_bpm")?.let { "最低心率：$it bpm" },
            payload?.long("max_bpm")?.let { "最高心率：$it bpm" },
            payload?.long("measurements")?.let { "测量次数：$it" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.resting_heart_rate_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("average_bpm")?.let { "静息心率：$it bpm" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.oxygen_saturation_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.double("average_percent")?.let { "平均血氧：${it.percent()}" },
            payload?.double("min_percent")?.let { "最低血氧：${it.percent()}" },
            payload?.long("measurements")?.let { "测量次数：$it" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.hrv_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.double("average_ms")?.let { "HRV（压力参考）：${it.roundToInt()} ms" },
            payload?.long("measurements")?.let { "测量次数：$it" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.active_calories_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("active_calories_kcal")?.let { "活动热量：$it 千卡" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.distance_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("distance_meters")?.let { "距离：$it 米" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.floors_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("floors")?.let { "爬楼：$it 层" },
            payload?.text("data_origins")?.let { "数据来源：$it" },
        )

        "health.stress_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("average_score")?.let { "平均压力指数：$it" },
            payload?.long("min_score")?.let { "最低压力指数：$it" },
            payload?.long("max_score")?.let { "最高压力指数：$it" },
            payload?.long("measurements")?.let { "测量次数：$it" },
            payload?.text("data_source")?.let { "数据来源：$it" },
        )

        "health.standing_daily" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.long("stand_hours")?.let { "有效站立小时：$it" },
            payload?.long("stand_events")?.let { "站立次数：$it" },
            payload?.long("longest_sedentary_minutes")
                ?.let { "最长连续未站立（估算）：$it 分钟" },
            payload?.text("data_source")?.let { "数据来源：$it" },
        )

        "health.sync_window" -> listOfNotNull(
            payload?.text("date")?.let { "日期：$it" },
            payload?.text("window")?.let { "同步窗口：$it" },
        )

        "share.inbox_item" -> buildList {
            payload?.text("text")?.let { add("分享内容：$it") }
            val names = payload?.get("attachments")?.jsonArray.orEmpty().mapNotNull { attachment ->
                attachment.jsonObject.text("name")
            }
            if (names.isNotEmpty()) add("附件：${names.joinToString("、")}")
        }

        else -> emptyList()
    }
    val evidence = details.ifEmpty { listOf(summary) }.joinToString("\n")
    return """
        请结合下面这条由我明确选择的手机数据线索，帮我判断它为什么值得关注，并给出下一步建议。先区分事实与推断；信息不足时请提问，不要自行补全。未经我确认，不要执行对外发送、修改日程或其他有副作用的操作。

        $evidence
    """.trimIndent()
}

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun kotlinx.serialization.json.JsonObject.long(key: String): Long? =
    get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun kotlinx.serialization.json.JsonObject.double(key: String): Double? =
    get(key)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun Double.percent(): String = "${roundToInt()}%"

private fun formatTime(epochMillis: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)
