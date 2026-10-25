package com.octopmobile.app.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务端 → 客户端的事件（docs/protocol.md §4.2）。
 *
 * 刻意**不用 sealed class 多态反序列化**：上游事件类型会随版本增加，遇到未知 `type`
 * 必须是「忽略这一帧」，而不是「抛异常把整条连接搞挂」。所以统一先解成 [JsonObject]，
 * 认得出就转具体类型，认不出就落到 [Unknown]。
 */
sealed interface ServerEvent {

    /** 帧的 `type` 原文，用于日志与兜底分支。 */
    val type: String

    /** 帧上带的 `thread_id`（不是每帧都有）。 */
    val threadId: String?

    /** `turn.started` / `turn.resumed` —— 回合开始与续跑，[phase] 区分。 */
    data class TurnStarted(
        override val type: String,
        override val threadId: String?,
        val turnId: String?,
        val seq: Long?,
        val phase: String?,
    ) : ServerEvent

    /**
     * `turn.ended` —— **这才是回合真正结束**。
     * `done` 只是当前这段流的结束标记，steer 续跑时会有多段（protocol.md §8 第 5 条）。
     */
    data class TurnEnded(
        override val threadId: String?,
        val turnId: String?,
        val failed: Boolean,
        val hitlPaused: Boolean,
    ) : ServerEvent {
        override val type: String get() = "turn.ended"
    }

    /** `token` —— 正文增量，纯追加。注意字段是 `content` 而不是 `text`。 */
    data class Token(
        override val threadId: String?,
        val content: String,
    ) : ServerEvent {
        override val type: String get() = "token"
    }

    /** `reasoning` —— 思考增量，纯追加。 */
    data class Reasoning(
        override val threadId: String?,
        val content: String,
    ) : ServerEvent {
        override val type: String get() = "reasoning"
    }

    /**
     * `tool_call_chunk` —— [name] 与 [args] **都是流式片段**，必须按 [index] 缓冲累加后再解析
     * （protocol.md §8 第 3 条）。
     */
    data class ToolCallChunk(
        override val threadId: String?,
        val index: Int,
        val name: String,
        val args: String,
        val displayName: String?,
    ) : ServerEvent {
        override val type: String get() = "tool_call_chunk"
    }

    /**
     * `tool_result` —— 工具执行结果。
     *
     * [raw] **就是 chunk 本身**：harness 会把它连同 `messages`（结果体）原样透传，字段随版本变，
     * 所以不拆成强类型，展示时用 [outputText] 尽力抽取。
     */
    data class ToolResult(
        override val threadId: String?,
        val name: String?,
        val displayName: String?,
        val raw: JsonObject,
    ) : ServerEvent {
        override val type: String get() = "tool_result"
    }

    /**
     * `hitl_required` —— 需要人工确认，回合在此挂起。
     * 这是「可派活」闭环的关键一环（docs/spec.md §11）。
     *
     * [request] 是帧里的 `request` 对象（**已归一化**，`action_requests` 在顶层；
     * 见 `hitl/format.py:23-41`）。首轮挂起时它**不带 `pending_id`**，续跑时才补写。
     */
    data class HitlRequired(
        override val threadId: String?,
        val pendingId: String?,
        val request: JsonObject,
    ) : ServerEvent {
        override val type: String get() = "hitl_required"
    }

    /** `done` —— 当前这段流结束。 */
    data class Done(
        override val threadId: String?,
        val conversationMode: String?,
        val pendingPlanPath: String?,
    ) : ServerEvent {
        override val type: String get() = "done"
    }

    /** `error`。 */
    data class Error(
        override val threadId: String?,
        val message: String,
    ) : ServerEvent {
        override val type: String get() = "error"
    }

    /**
     * `turn_status` —— `subscribe` 之后的应答。
     *
     * 上游**没有** `state_snapshot`，「这个线程现在是否在跑」只能靠这一帧的 [active] 判断
     * （protocol.md §4.2）。
     */
    data class TurnStatus(
        override val threadId: String,
        val active: Boolean,
    ) : ServerEvent {
        override val type: String get() = "turn_status"
    }

    /** `pong`。 */
    data object Pong : ServerEvent {
        override val type: String get() = "pong"
        override val threadId: String? get() = null
    }

    /** `user` —— 服务端回显的一轮用户输入（含 steer 合并后的）。 */
    data class UserEcho(
        override val threadId: String?,
        val content: String?,
        val messageId: String?,
        val steer: Boolean,
        val raw: JsonObject,
    ) : ServerEvent {
        override val type: String get() = "user"
    }

    /** `attachment` —— 内联附件，`data` 是 base64。 */
    data class Attachment(
        override val threadId: String?,
        val kind: String?,
        val mimeType: String?,
        val filename: String?,
        val altText: String?,
        val data: String?,
    ) : ServerEvent {
        override val type: String get() = "attachment"
    }

    /** `state_update` / 其他只用于分段的状态帧。 */
    data class State(
        override val type: String,
        override val threadId: String?,
        val node: String?,
        val raw: JsonObject,
    ) : ServerEvent

    /** `steer.*` 全族的兜底表示，字段按需从 [raw] 取。 */
    data class Steer(
        override val type: String,
        override val threadId: String?,
        val clientPendingId: String?,
        val pendingId: String?,
        val turnId: String?,
        val raw: JsonObject,
    ) : ServerEvent

    /** 认不出的帧：只记录，不中断连接。 */
    data class Unknown(
        override val type: String,
        override val threadId: String?,
        val raw: JsonObject,
    ) : ServerEvent

    companion object {

        /**
         * 把一帧原始文本解成 [ServerEvent]。**不抛异常** —— 任何解析问题都归为 [Unknown]，
         * 由调用方决定是否打日志。
         */
        fun decode(json: Json, text: String): ServerEvent {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return Unknown("<malformed>", null, JsonObject(emptyMap()))

            val type = obj["type"]?.jsonPrimitive?.contentOrNullSafe() ?: "<missing-type>"
            val threadId = obj.stringOrNull("thread_id")

            fun raw() = obj

            return when (type) {
                "turn.started", "turn.resumed" -> TurnStarted(
                    type = type,
                    threadId = threadId,
                    turnId = obj.stringOrNull("turn_id"),
                    seq = obj.longOrNull("seq"),
                    phase = obj.stringOrNull("phase"),
                )

                "turn.ended" -> TurnEnded(
                    threadId = threadId,
                    turnId = obj.stringOrNull("turn_id"),
                    failed = obj["failed"]?.jsonPrimitive?.contentOrNullSafe()
                        ?.let { it != "false" && it != "null" } == true,
                    hitlPaused = obj["hitl_paused"]?.jsonPrimitive?.contentOrNullSafe() == "true",
                )

                "token" -> Token(threadId, obj.stringOrNull("content").orEmpty())
                "reasoning" -> Reasoning(threadId, obj.stringOrNull("content").orEmpty())

                "tool_call_chunk" -> ToolCallChunk(
                    threadId = threadId,
                    index = obj.longOrNull("index")?.toInt() ?: 0,
                    name = obj.stringOrNull("name").orEmpty(),
                    args = obj.stringOrNull("args").orEmpty(),
                    displayName = obj.stringOrNull("display_name"),
                )

                "tool_result" -> ToolResult(
                    threadId = threadId,
                    name = obj.stringOrNull("name"),
                    displayName = obj.stringOrNull("display_name"),
                    raw = raw(),
                )

                "hitl_required" -> {
                    val request = obj["request"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        ?: JsonObject(emptyMap())
                    HitlRequired(
                        threadId = threadId,
                        pendingId = request.stringOrNull("pending_id"),
                        request = request,
                    )
                }

                "done" -> Done(
                    threadId = threadId,
                    conversationMode = obj.stringOrNull("conversation_mode"),
                    pendingPlanPath = obj.stringOrNull("pending_plan_path"),
                )

                "error" -> Error(threadId, obj.stringOrNull("message").orEmpty())

                "turn_status" -> TurnStatus(
                    threadId = threadId.orEmpty(),
                    active = obj["active"]?.jsonPrimitive?.contentOrNullSafe() == "true",
                )

                "pong" -> Pong

                "user" -> UserEcho(
                    threadId = threadId,
                    content = obj.stringOrNull("content"),
                    messageId = obj.stringOrNull("message_id"),
                    steer = runCatching {
                        obj["meta"]?.jsonObject?.stringOrNull("steer")
                    }.getOrNull() == "true",
                    raw = raw(),
                )

                "attachment" -> Attachment(
                    threadId = threadId,
                    kind = obj.stringOrNull("kind"),
                    mimeType = obj.stringOrNull("mime_type"),
                    filename = obj.stringOrNull("filename"),
                    altText = obj.stringOrNull("alt_text"),
                    data = obj.stringOrNull("data"),
                )

                "state_update" -> State(
                    type = type,
                    threadId = threadId,
                    node = obj.stringOrNull("node"),
                    raw = raw(),
                )

                else -> if (type.startsWith("steer.")) {
                    Steer(
                        type = type,
                        threadId = threadId,
                        clientPendingId = obj.stringOrNull("client_pending_id"),
                        pendingId = obj.stringOrNull("pending_id"),
                        turnId = obj.stringOrNull("turn_id"),
                        raw = raw(),
                    )
                } else {
                    Unknown(type, threadId, raw())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 宽松取值

/**
 * 从 `tool_result` 的结果体里尽力抽出可展示的文本。
 *
 * 上游的形状不止一种（`turn_failure.py:107-133`）：可能是 `messages: [{content}]`，
 * 也可能是扁平的 `content` / `error`；`content` 又可能是字符串或 block 数组。
 * 抽不到就返回 null —— 卡片只显示工具名，而不是假装结果为空。
 */
val ServerEvent.ToolResult.outputText: String?
    get() {
        val fromMessages = (raw["messages"] as? JsonArray).orEmpty()
            .mapNotNull { contentText((it as? JsonObject)?.get("content")) }
            .filter { it.isNotBlank() }
        if (fromMessages.isNotEmpty()) return fromMessages.joinToString("\n")
        return contentText(raw["content"]) ?: contentText(raw["error"])
    }

/** 工具执行失败（`status == "error"`），UI 上标红。 */
val ServerEvent.ToolResult.isError: Boolean
    get() = raw.stringOrNull("status") == "error" ||
        (raw["messages"] as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.stringOrNull("status") == "error"
        }

private fun contentText(element: JsonElement?): String? = when (element) {
    null -> null
    is JsonPrimitive -> if (element is JsonNull) null else element.content.takeIf { it.isNotBlank() }
    is JsonArray -> element.mapNotNull { contentText(it) }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { null }

    is JsonObject -> contentText(element["text"]) ?: contentText(element["output"])
    else -> null
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else content

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNullSafe()

private fun JsonObject.longOrNull(key: String): Long? =
    this[key]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull()
