package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.ConversationTaskEntity
import com.octopmobile.app.data.db.ConversationTaskStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object ConversationTaskAction {
    const val COMPLETE = "complete"
    const val DEFER = "defer"
    const val IGNORE = "ignore"
}

/** 服务端把延后时间写在 capsule 里，客户端不额外维护一份状态。 */
internal fun ConversationTaskEntity.deferredUntilMillis(): Long? {
    val seconds = runCatching {
        Json.parseToJsonElement(capsuleJson)
            .jsonObject["deferred_until"]
            ?.jsonPrimitive
            ?.longOrNull
    }.getOrNull() ?: return null
    return seconds * 1000
}

internal fun ConversationTaskEntity.isDeferred(nowMillis: Long): Boolean =
    (deferredUntilMillis() ?: 0L) > nowMillis

internal fun ConversationTaskEntity.canActOn(): Boolean =
    status == ConversationTaskStatus.OPEN ||
        status == ConversationTaskStatus.IN_PROGRESS ||
        status == ConversationTaskStatus.NEEDS_USER
