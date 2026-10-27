package com.octopmobile.app.data.mobile

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
            payload?.text("data_origin")?.let { "数据来源：$it" },
        )

        "health.exercise_session" -> listOfNotNull(
            payload?.long("starts_at")?.let { "开始时间：${formatTime(it)}" },
            payload?.long("ends_at")?.let { "结束时间：${formatTime(it)}" },
            payload?.long("exercise_type")?.let { "运动类型：$it" },
            payload?.text("data_origin")?.let { "数据来源：$it" },
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

private fun formatTime(epochMillis: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)
