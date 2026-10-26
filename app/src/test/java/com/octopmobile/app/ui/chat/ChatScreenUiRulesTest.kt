package com.octopmobile.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenUiRulesTest {

    @Test
    fun `untouched new conversation is removed on exit`() {
        assertTrue(
            shouldDeleteUntouchedConversation(
                isNewConversation = true,
                hasMessages = false,
            ),
        )
    }

    @Test
    fun `conversation with interaction is kept on exit`() {
        assertFalse(
            shouldDeleteUntouchedConversation(
                isNewConversation = true,
                hasMessages = true,
            ),
        )
        assertFalse(
            shouldDeleteUntouchedConversation(
                isNewConversation = false,
                hasMessages = false,
            ),
        )
    }
}
