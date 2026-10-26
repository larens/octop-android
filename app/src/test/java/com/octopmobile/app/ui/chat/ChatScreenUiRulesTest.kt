package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.ThreadEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenUiRulesTest {

    @Test
    fun `hides bottom workbench chrome when ime is visible`() {
        assertFalse(shouldShowWorkbenchBar(imeVisible = true, inputFocused = false))
        assertFalse(shouldApplyChatBottomBarNavigationPadding(imeVisible = true))
    }

    @Test
    fun `hides bottom workbench chrome while input is focused`() {
        assertFalse(shouldShowWorkbenchBar(imeVisible = false, inputFocused = true))
    }

    @Test
    fun `keeps bottom workbench chrome when input is inactive`() {
        assertTrue(shouldShowWorkbenchBar(imeVisible = false, inputFocused = false))
        assertTrue(shouldApplyChatBottomBarNavigationPadding(imeVisible = false))
    }

    @Test
    fun `model selector follows input focus`() {
        assertTrue(shouldShowModelSelector(inputFocused = true))
        assertFalse(shouldShowModelSelector(inputFocused = false))
    }

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

    @Test
    fun `fresh thread hides top right back action`() {
        assertFalse(
            shouldShowChatBackAction(
                messages = emptyList(),
                thread = thread(title = null),
            ),
        )
    }

    @Test
    fun `existing conversation keeps top right back action`() {
        assertTrue(
            shouldShowChatBackAction(
                messages = listOf(message()),
                thread = thread(title = null),
            ),
        )
        assertTrue(
            shouldShowChatBackAction(
                messages = emptyList(),
                thread = thread(title = "新会话之外"),
            ),
        )
    }

    private fun thread(title: String?) = ThreadEntity(
        threadId = "thread-1",
        agentId = "agent-1",
        title = title,
        lastMessage = null,
        pinned = false,
        lastActiveAt = 1L,
        createdAt = 1L,
        unreadCount = 0,
        modelRef = null,
        conversationMode = "ask",
        turnActive = false,
        awaitingUser = false,
    )

    private fun message() = MessageEntity(
        id = "msg-1",
        threadId = "thread-1",
        turnId = null,
        role = "assistant",
        sortOrder = 1L,
        partsJson = "[]",
        status = "done",
        errorInfo = null,
        streamInterruptCount = 0,
        updatedAt = 1L,
    )
}
