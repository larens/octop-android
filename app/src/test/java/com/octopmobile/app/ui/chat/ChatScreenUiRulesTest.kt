package com.octopmobile.app.ui.chat

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
}
