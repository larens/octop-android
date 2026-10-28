package com.octopmobile.app.data.repo

import com.octopmobile.app.data.remote.dto.ThreadDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadVisibilityTest {
    @Test
    fun userConversationsNeedMessagesOrActiveWork() {
        assertTrue(
            isVisibleConversation(
                ThreadDto(
                    threadId = "chat",
                    channelType = "dashboard",
                    hasMessages = true,
                ),
            ),
        )
        assertTrue(
            isVisibleConversation(
                ThreadDto(
                    threadId = "active",
                    channelType = "dashboard",
                    isActive = true,
                ),
            ),
        )
        assertFalse(
            isVisibleConversation(
                ThreadDto(
                    threadId = "stale-empty",
                    channelType = "dashboard",
                ),
            ),
        )
        assertTrue(
            isVisibleConversation(
                ThreadDto(
                    threadId = "cron",
                    channelType = "cron",
                    hasMessages = true,
                ),
            ),
        )
        assertFalse(
            isVisibleConversation(
                ThreadDto(
                    threadId = "empty-cron",
                    channelType = "cron",
                ),
            ),
        )
        assertFalse(
            isVisibleConversation(
                ThreadDto(
                    threadId = "im",
                    channelType = "feishu",
                    hasMessages = true,
                ),
            ),
        )
        assertFalse(
            isVisibleConversation(
                ThreadDto(
                    threadId = "task-draft",
                    channelType = "dashboard",
                    sessionKey = "agent:dashboard:android-task-123:dm",
                    hasMessages = true,
                ),
            ),
        )
    }

    @Test
    fun onlyStaleEmptyDashboardThreadsAreDeleted() {
        assertTrue(
            shouldDeleteEmptyServerThread(
                ThreadDto(threadId = "stale", channelType = "dashboard"),
            ),
        )
        assertFalse(
            shouldDeleteEmptyServerThread(
                ThreadDto(threadId = "active", channelType = "dashboard", isActive = true),
            ),
        )
        assertFalse(
            shouldDeleteEmptyServerThread(
                ThreadDto(threadId = "busy", channelType = "dashboard", turnActive = true),
            ),
        )
        assertFalse(
            shouldDeleteEmptyServerThread(
                ThreadDto(threadId = "pinned", channelType = "dashboard", pinned = true),
            ),
        )
        assertFalse(
            shouldDeleteEmptyServerThread(
                ThreadDto(threadId = "history", channelType = "dashboard", hasMessages = true),
            ),
        )
        assertFalse(
            shouldDeleteEmptyServerThread(
                ThreadDto(threadId = "cron", channelType = "cron"),
            ),
        )
    }
}
