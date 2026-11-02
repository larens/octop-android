package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.model.Part
import com.octopmobile.app.data.model.PartType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pull a completed order's pickup code out of MCP tool output.
 *
 * The chat transcript intentionally collapses tool details under the thinking section. A
 * successful coffee order still needs the one value the user must see at the counter at a glance.
 */
internal fun pickupCodeFrom(parts: List<Part>): String? {
    for (part in parts) {
        if (part.type != PartType.TOOL_RESULT || !part.errorCode.isNullOrBlank()) continue
        for (raw in listOfNotNull(part.output, part.text)) {
            extractPickupCode(raw)?.let { return it }
        }
    }
    return null
}

internal fun extractPickupCode(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    if (parsed != null) {
        if (hasFailureSignal(parsed)) return null
        findInJson(parsed)?.let { return it }
        val nested = (parsed as? JsonPrimitive)?.contentOrNull?.trim()
        if (!nested.isNullOrEmpty() && nested != text && nested.startsWithAny('{', '[')) {
            findInJson(runCatching { Json.parseToJsonElement(nested) }.getOrNull())?.let {
                return it
            }
        }
    }
    if (hasFailureText(text)) return null
    return scanText(text)
}

private fun findInJson(element: JsonElement?): String? = when (element) {
    is JsonObject -> {
        pickupFields.firstNotNullOfOrNull { key ->
            (element[key] as? JsonPrimitive)?.contentOrNull?.let(::codeFromValue)
        } ?: pickupCodeInfoFields.firstNotNullOfOrNull { key ->
            val info = element[key] as? JsonObject ?: return@firstNotNullOfOrNull null
            (info["code"] as? JsonPrimitive)?.contentOrNull?.let(::codeFromValue)
        } ?: element.values.firstNotNullOfOrNull(::findInJson)
    }

    is JsonArray -> element.firstNotNullOfOrNull(::findInJson)

    is JsonPrimitive -> {
        val text = element.contentOrNull?.trim().orEmpty()
        if (!text.startsWithAny('{', '[')) {
            null
        } else {
            findInJson(runCatching { Json.parseToJsonElement(text) }.getOrNull())
        }
    }

    else -> null
}

private fun codeFromValue(value: String): String? =
    normalizeCode(value) ?: scanText(value)

private fun hasFailureSignal(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.entries.any { (key, value) ->
        when {
            key in failureStatusFields -> value.asFailureStatus()
            key in failureBooleanFields -> (value as? JsonPrimitive)?.contentOrNull == "false"
            key == "error" -> value.asMeaningfulError()
            else -> hasFailureSignal(value)
        }
    }

    is JsonArray -> element.any(::hasFailureSignal)
    else -> false
}

private fun JsonElement.asFailureStatus(): Boolean =
    (this as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.lowercase()
        ?.let { it in failureStatuses }
        ?: false

private fun JsonElement.asMeaningfulError(): Boolean = when (this) {
    is JsonPrimitive -> contentOrNull?.trim()?.let {
        it.isNotEmpty() && !it.equals("null", ignoreCase = true) &&
            !it.equals("false", ignoreCase = true)
    } == true

    is JsonObject -> isNotEmpty()
    is JsonArray -> isNotEmpty()
}

private fun hasFailureText(text: String): Boolean =
    PICKUP_FAILURE_TEXT_RE.containsMatchIn(text)

private fun normalizeCode(value: String): String? {
    val code = value.trim()
        .trim('"', '\'', '：', ':', '#', ' ')
        .filterNot(Char::isWhitespace)
    if (code.length !in MIN_PICKUP_CODE_LENGTH..MAX_PICKUP_CODE_LENGTH) return null
    if (code.none(Char::isDigit) || !PICKUP_CODE_RE.matches(code)) return null
    return code
}

private fun scanText(text: String): String? =
    PICKUP_TEXT_RE.find(text)?.groupValues?.getOrNull(1)?.let(::normalizeCode)

private fun String.startsWithAny(vararg prefixes: Char): Boolean =
    isNotEmpty() && this[0] in prefixes

private val pickupFields = listOf(
    "pickup_code",
    "pickupCode",
    "pickup_no",
    "pickupNo",
    "meal_code",
    "mealCode",
    "take_code",
    "takeCode",
    "takeMealCode",
    "take_meal_code",
    "取餐码",
    "取餐号",
)
private val pickupCodeInfoFields = listOf(
    "takeMealCodeInfo",
    "take_meal_code_info",
    "mealCodeInfo",
    "meal_code_info",
)
private val failureStatusFields = setOf(
    "status",
    "order_status",
    "orderStatus",
    "state",
)
private val failureBooleanFields = setOf("success", "ok")
private val failureStatuses = setOf(
    "failed",
    "failure",
    "error",
    "cancelled",
    "canceled",
    "rejected",
    "expired",
    "closed",
    "void",
    "refunded",
)
private val PICKUP_CODE_RE = Regex("""^[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*$""")
private val PICKUP_TEXT_RE = Regex(
    """(?:取餐码|取餐号|pickup\s*(?:code|no)|meal\s*code)\s*[:：#]?\s*([A-Za-z0-9][A-Za-z0-9_-]{2,19})""",
    RegexOption.IGNORE_CASE,
)
private val PICKUP_FAILURE_TEXT_RE = Regex(
    """(?:下单|支付|订单|制作|取餐|order|payment).{0,16}(?:失败|已取消|已关闭|failed|cancelled|canceled|rejected|expired)""",
    RegexOption.IGNORE_CASE,
)
private const val MIN_PICKUP_CODE_LENGTH = 3
private const val MAX_PICKUP_CODE_LENGTH = 20
