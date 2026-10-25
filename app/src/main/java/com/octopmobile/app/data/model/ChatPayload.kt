package com.octopmobile.app.data.model

import com.octopmobile.app.data.remote.dto.HitlDecision
import com.octopmobile.app.data.ws.UserContentBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 已上传到服务端 workspace 的附件（docs/protocol.md §6）。
 *
 * 发送时放进 `user_turn.messages` 的 content block，**不走 `text`** —— 上游就是靠
 * content block 里的 `workspace_path` / `filename` / `media_type` 认附件的。
 */
data class UploadedAttachment(
    val workspacePath: String,
    val filename: String,
    val mediaType: String,
) {
    val isImage: Boolean get() = mediaType.startsWith("image/")

    fun toBlock(): UserContentBlock = UserContentBlock(
        type = if (isImage) UserContentBlock.IMAGE else UserContentBlock.FILE,
        workspacePath = workspacePath,
        filename = filename,
        mediaType = mediaType,
    )
}

/**
 * 一张待人工确认的 HITL 卡（`hitl_required` / 历史的 `hitl_pending`）。
 *
 * 两个来源的 JSON 形状一致（`hitl/coordinator.py:176-180` 与 `stream_compat.py:34`）：
 * `{"pending_id"?, "action_requests": [...], "review_configs": [...]}`。
 * 首轮挂起时 **没有 `pending_id`**（上游只在续跑时补写），所以字段可空 —— 提交时让服务端
 * 按 thread 解析（`chat/routes.py:239-245`）。
 */
data class HitlPending(
    val pendingId: String?,
    val actions: List<HitlAction>,
    /** 是否是 `ask_user_question` 这类「提问」而非「工具授权」。 */
    val isQuestion: Boolean,
    /** 是否所有动作都允许 `approve`（看 `review_configs[].allowed_decisions`）。 */
    val canApprove: Boolean,
)

/** 一条待授权的工具调用。 */
data class HitlAction(
    val name: String,
    val args: String?,
    val description: String?,
)

/** `ask_user_question` 的 HITL 是提问，走 `respond` 而不是 `approve`（`hitl/format.py:14`）。 */
const val ASK_USER_TOOL = "ask_user_question"

/** 把 `hitl_required.request` 或历史的 `hitl_pending` 对象解成卡片模型；解不出返回 null。 */
fun parseHitlPending(raw: JsonObject?): HitlPending? {
    if (raw == null) return null
    val actions = (raw["action_requests"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = obj.string("name") ?: return@mapNotNull null
        HitlAction(
            name = name,
            args = obj["args"]?.let { renderArgs(it) },
            description = obj.string("description"),
        )
    }
    if (actions.isEmpty()) return null
    return HitlPending(
        pendingId = raw.string("pending_id"),
        actions = actions,
        isQuestion = actions.any { it.name == ASK_USER_TOOL },
        canApprove = canApprove(raw, actions),
    )
}

/**
 * 上游对不合法决定直接 `HTTPException(400)`（`hitl/coordinator.py:183-214`），所以按钮该不该
 * 给要看 `allowed_decisions`。没给配置、或某个动作没被 `allowed_decisions` 覆盖时，按上游
 * 语义（`_action_allows_approve`）一律放行。
 */
private fun canApprove(raw: JsonObject, actions: List<HitlAction>): Boolean {
    val configs = raw["review_configs"] as? JsonArray ?: return true
    val allowedByAction = configs.mapNotNull { element ->
        val config = element as? JsonObject ?: return@mapNotNull null
        val name = config.string("action_name") ?: return@mapNotNull null
        val allowed = (config["allowed_decisions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (allowed.isEmpty()) null else name to allowed.toSet()
    }.toMap()

    return actions.none { action ->
        allowedByAction[action.name]?.contains(HitlDecision.APPROVE) == false
    }
}

/** `args` 可能是对象、字符串或字符串化的 JSON，统一渲染成给人看的多行文本。 */
private fun renderArgs(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() && it != "{}" }
    is JsonObject -> element.toString().takeIf { it != "{}" }
    is JsonArray -> element.takeIf { it.isNotEmpty() }?.toString()
    else -> null
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
