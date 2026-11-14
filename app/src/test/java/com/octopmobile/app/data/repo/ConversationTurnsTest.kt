package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.MessageStatus
import com.octopmobile.app.data.model.Part
import com.octopmobile.app.data.model.PartType
import com.octopmobile.app.data.model.PartsCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTurnsTest {
    private val json = Json

    @Test
    fun assistantAndToolRowsResolveToTheirUserTurn() {
        val messages = listOf(
            message("u1", "user", 1),
            message("a1", "assistant", 2),
            message("t1", "tool", 3),
            message("a2", "assistant", 4),
            message("u2", "user", 5),
            message("a3", "assistant", 6),
        )

        val turn = conversationTurnFor(messages, "t1")!!

        assertEquals("u1", turn.anchorMessageId)
        assertEquals(listOf("u1", "a1", "t1", "a2"), turn.messageIds)
        assertTrue(turn.isPersisted)
    }

    @Test
    fun localPlaceholderPreventsDeletionUntilHistoryIsPersisted() {
        val turn = conversationTurnFor(
            listOf(message("local-user-1", "user", 1), message("a1", "assistant", 2)),
            "a1",
        )!!

        assertFalse(turn.isPersisted)
    }

    @Test
    fun unmatchedLocalUserKeepsItsTurnUntilServerHistoryContainsIt() {
        val reconciliation = reconcileLocalUserRows(
            localUsers = listOf(user("local-user-1", "刚发出的问题", 100)),
            serverEntities = listOf(user("old-user", "更早的问题", 10)),
            json = json,
        )

        assertTrue(reconciliation.confirmedIds.isEmpty())
        assertTrue(reconciliation.awaitingServer)
    }

    @Test
    fun matchedUserWithoutReplyStillKeepsLocalPlaceholders() {
        val reconciliation = reconcileLocalUserRows(
            localUsers = listOf(user("local-user-1", "刚发出的问题", 100)),
            serverEntities = listOf(user("server-user", "刚发出的问题", 99)),
            json = json,
        )

        assertEquals(listOf("local-user-1"), reconciliation.confirmedIds)
        assertTrue(reconciliation.awaitingServer)
    }

    @Test
    fun matchedUserWithReplyConfirmsTheWholeTurn() {
        val reconciliation = reconcileLocalUserRows(
            localUsers = listOf(user("local-user-1", "刚发出的问题", 100)),
            serverEntities = listOf(
                user("server-user", "刚发出的问题", 99),
                message("server-reply", "assistant", 101),
            ),
            json = json,
        )

        assertEquals(listOf("local-user-1"), reconciliation.confirmedIds)
        assertFalse(reconciliation.awaitingServer)
    }

    @Test
    fun olderMessageWithSameTextDoesNotConfirmANewSend() {
        val reconciliation = reconcileLocalUserRows(
            localUsers = listOf(user("local-user-1", "继续", 10_000_000)),
            serverEntities = listOf(
                user("old-user", "继续", 1_000_000),
                message("old-reply", "assistant", 1_000_001),
            ),
            json = json,
        )

        assertTrue(reconciliation.confirmedIds.isEmpty())
        assertTrue(reconciliation.awaitingServer)
    }

    private fun user(id: String, text: String, order: Long) = message(id, "user", order).copy(
        partsJson = PartsCodec.encode(json, listOf(Part(type = PartType.TEXT, text = text))),
    )

    private fun message(id: String, role: String, order: Long) = MessageEntity(
        id = id,
        threadId = "thread-1",
        turnId = null,
        role = role,
        sortOrder = order,
        partsJson = "[]",
        status = MessageStatus.DONE,
        errorInfo = null,
        streamInterruptCount = 0,
        updatedAt = order,
    )
}
