package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTurnsTest {
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
