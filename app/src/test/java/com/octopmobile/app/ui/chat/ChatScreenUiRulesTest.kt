package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.db.ConversationEventEntity
import com.octopmobile.app.data.db.ConversationTaskEntity
import org.junit.Assert.assertEquals
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

    @Test
    fun `background worker tasks do not block chat input`() {
        assertFalse(
            shouldBlockChatInput(
                turnActive = false,
                hasPendingHitl = false,
                activeRouterTaskCount = 3,
            ),
        )
        assertTrue(
            shouldBlockChatInput(
                turnActive = true,
                hasPendingHitl = false,
                activeRouterTaskCount = 0,
            ),
        )
    }

    @Test
    fun `only active read-only tasks are projected from the latest worker event`() {
        val tasks = listOf(
            task(
                id = "worker-1",
                type = "read_only",
                status = "in_progress",
            ),
            task(
                id = "worker-2",
                type = "read_only",
                status = "in_progress",
            ),
            task(
                id = "done-1",
                type = "read_only",
                status = "done",
            ),
            task(
                id = "write-1",
                type = "calendar_write",
                status = "in_progress",
            ),
        )
        val events = listOf(
            event(taskId = "worker-1", seq = 1, type = "worker.started"),
            event(taskId = "worker-1", seq = 2, type = "worker.progress"),
            event(taskId = "worker-2", seq = 3, type = "worker.retry_scheduled"),
        )

        val projected = projectActiveRouterTasks(tasks, events)

        assertEquals(listOf("worker-1", "worker-2"), projected.map { it.taskId })
        assertEquals(RouterTaskProgress.GATHERING, projected[0].progress)
        assertEquals(RouterTaskProgress.RETRYING, projected[1].progress)
    }

    @Test
    fun `latest completed worker result stays visible for user closure`() {
        val now = 2_000_000L
        val done = task(
            id = "done-1",
            type = "read_only",
            status = "done",
            updatedAt = now - 1_000,
        )
        val failed = task(
            id = "failed-1",
            type = "read_only",
            status = "failed",
            updatedAt = now - 500,
        )
        val stale = task(
            id = "stale-1",
            type = "read_only",
            status = "done",
            updatedAt = now - 25 * 60 * 60 * 1_000L,
        )
        val events = listOf(
            event(
                taskId = "done-1",
                seq = 4,
                type = "worker.completed",
                summary = "Tomorrow has one calendar conflict at 10:00.",
            ),
            event(
                taskId = "failed-1",
                seq = 5,
                type = "worker.failed",
                summary = "Token budget exhausted.",
            ),
            event(
                taskId = "stale-1",
                seq = 6,
                type = "worker.completed",
                summary = "Stale result",
            ),
        )

        val result = projectLatestRouterTaskResult(
            tasks = listOf(done, failed, stale),
            events = events,
            nowMillis = now,
        )

        assertEquals("failed-1", result?.taskId)
        assertTrue(result?.failed == true)
        assertEquals("Token budget exhausted.", result?.summary)
    }

    private fun task(
        id: String,
        type: String,
        status: String,
        updatedAt: Long = 2,
    ) = ConversationTaskEntity(
        taskId = id,
        conversationId = "conversation-1",
        taskType = type,
        title = id,
        subjectKey = id,
        objective = "Read information",
        status = status,
        requiresUser = false,
        capsuleJson = "{}",
        priority = 0,
        createdAt = 1,
        updatedAt = updatedAt,
        expiresAt = null,
    )

    private fun event(
        taskId: String,
        seq: Long,
        type: String,
        summary: String = type,
    ) = ConversationEventEntity(
        eventId = "event-$seq",
        conversationId = "conversation-1",
        traceId = "trace-$taskId",
        taskId = taskId,
        runId = "run-$taskId",
        parentEventId = null,
        seq = seq,
        schemaVersion = 1,
        eventType = type,
        status = "running",
        summary = summary,
        payloadRef = null,
        payloadJson = null,
        usageJson = "{}",
        createdAt = seq,
    )
}
