package com.octopmobile.app.data.repo

import com.octopmobile.app.data.remote.dto.ThreadDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadVisibilityTest {
    @Test
    fun onlyUserDashboardConversationsAreVisible() {
        assertTrue(isVisibleConversation(ThreadDto(threadId = "chat", channelType = "dashboard")))
        assertFalse(isVisibleConversation(ThreadDto(threadId = "cron", channelType = "cron")))
        assertFalse(
            isVisibleConversation(
                ThreadDto(
                    threadId = "task-draft",
                    channelType = "dashboard",
                    sessionKey = "agent:dashboard:android-task-123:dm",
                ),
            ),
        )
    }
}
