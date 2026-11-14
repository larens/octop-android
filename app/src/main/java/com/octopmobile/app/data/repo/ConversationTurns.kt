package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.model.PartType
import com.octopmobile.app.data.model.PartsCodec
import kotlinx.serialization.json.Json

data class ConversationTurn(
    val anchorMessageId: String,
    val messageIds: List<String>,
) {
    val isPersisted: Boolean get() = messageIds.isNotEmpty() && messageIds.none { it.startsWith("local-") }
}

/** A turn starts at a user message and includes every assistant/tool row before the next user message. */
internal fun conversationTurnFor(messages: List<MessageEntity>, selectedMessageId: String): ConversationTurn? {
    val selected = messages.indexOfFirst { it.id == selectedMessageId }
    if (selected < 0) return null

    val start = (selected downTo 0).firstOrNull { messages[it].role == "user" } ?: selected
    val endExclusive = ((start + 1)..<messages.size)
        .firstOrNull { messages[it].role == "user" }
        ?: messages.size
    val turnMessages = messages.subList(start, endExclusive)
    val anchor = turnMessages.firstOrNull { !it.id.startsWith("local-") } ?: return null
    return ConversationTurn(
        anchorMessageId = anchor.id,
        messageIds = turnMessages.map { it.id },
    )
}

/**
 * 本地用户行与服务端历史里的用户行是否指同一条输入。
 *
 * 正文优先；纯附件消息没有正文时退到文件名序列，避免附件的远端引用字段差异导致误判。
 */
internal fun sameUserContent(local: MessageEntity, server: MessageEntity, json: Json): Boolean {
    val localParts = PartsCodec.decode(json, local.partsJson)
    val serverParts = PartsCodec.decode(json, server.partsJson)
    val localText = localParts.filter { it.type == PartType.TEXT }
        .mapNotNull { it.text }
        .filter { it.isNotEmpty() }
    val serverText = serverParts.filter { it.type == PartType.TEXT }
        .mapNotNull { it.text }
        .filter { it.isNotEmpty() }
    if (localText.isNotEmpty() || serverText.isNotEmpty()) return localText == serverText

    val localFiles = localParts.filter { it.type == PartType.ATTACHMENT }
        .mapNotNull { it.filename }
        .filter { it.isNotBlank() }
    val serverFiles = serverParts.filter { it.type == PartType.ATTACHMENT }
        .mapNotNull { it.filename }
        .filter { it.isNotBlank() }
    return localFiles.isNotEmpty() && localFiles == serverFiles
}

internal data class LocalUserReconciliation(
    val confirmedIds: List<String>,
    /** 还有本地提问没被服务端历史完整承接（没对上，或对上了但后面还没有回复）。 */
    val awaitingServer: Boolean,
)

/** 服务端消息时间允许比本地发送时间早这么多；超出就不认为它是这次发送。 */
private const val LOCAL_SEND_MATCH_WINDOW_MS = 2 * 60 * 1000L

/**
 * 把本地先落的用户行跟服务端历史页对齐。
 *
 * `serverEntities` 已经是按服务端顺序排好的一页；本地行只有在同内容服务端行之后
 * 至少出现一条非用户行时，才算这一轮已经被完整承接。
 */
internal fun reconcileLocalUserRows(
    localUsers: List<MessageEntity>,
    serverEntities: List<MessageEntity>,
    json: Json,
): LocalUserReconciliation {
    val confirmedIds = mutableListOf<String>()
    var awaitingServer = false
    localUsers.forEach { local ->
        val match = serverEntities.withIndex()
            .filter { (_, server) ->
                server.role == "user" &&
                    server.sortOrder >= local.sortOrder - LOCAL_SEND_MATCH_WINDOW_MS &&
                    sameUserContent(local, server, json)
            }
            .minByOrNull { (_, server) -> kotlin.math.abs(server.sortOrder - local.sortOrder) }
        if (match == null) {
            awaitingServer = true
            return@forEach
        }
        confirmedIds += local.id
        if (serverEntities.drop(match.index + 1).none { it.role != "user" }) {
            awaitingServer = true
        }
    }
    return LocalUserReconciliation(confirmedIds = confirmedIds, awaitingServer = awaitingServer)
}
