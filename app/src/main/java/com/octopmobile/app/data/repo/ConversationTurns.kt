package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.MessageEntity

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
