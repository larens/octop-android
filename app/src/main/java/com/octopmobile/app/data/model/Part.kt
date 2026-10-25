package com.octopmobile.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 消息正文的最小单元，序列化后存进 `messages.parts_json`（docs/spec.md §8.1）。
 *
 * 与 WS 事件天然同构：`token` 落在 [PartType.TEXT]、`reasoning` 落在 [PartType.REASONING]、
 * 工具调用与结果各占一个 part。
 *
 * 和 `ContentBlockDto` 一样刻意做成「一个类 + type 判别」而不是 sealed class：上游的
 * block/事件字段会随版本变，多一个未知键不应该让整条消息读不出来。
 */
@Serializable
data class Part(
    val type: String,
    val text: String? = null,
    /** `tool_call` / `tool_result` 的工具名。 */
    val name: String? = null,
    /** `tool_call` 的入参（JSON 文本，可能是流式拼出来的）。 */
    val args: String? = null,
    /** `tool_result` 的输出摘要。 */
    val output: String? = null,
    val errorCode: String? = null,
    val filename: String? = null,
    val mime: String? = null,
    val url: String? = null,
)

object PartType {
    const val TEXT = "text"
    const val REASONING = "reasoning"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val ATTACHMENT = "attachment"
    const val ERROR = "error"
}

/** `parts_json` 的编解码。解析失败一律当成空 parts —— 坏一行不应该让整屏崩掉。 */
object PartsCodec {

    private val serializer = ListSerializer(Part.serializer())

    fun encode(json: Json, parts: List<Part>): String = json.encodeToString(serializer, parts)

    fun decode(json: Json, raw: String?): List<Part> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
