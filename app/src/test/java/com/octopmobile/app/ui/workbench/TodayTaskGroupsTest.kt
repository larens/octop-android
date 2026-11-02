package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.ConversationTaskEntity
import com.octopmobile.app.data.db.ConversationTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayTaskGroupsTest {
    @Test
    fun tasksAreGroupedByProductStateNotByWorkerThread() {
        val tasks = listOf(
            task("waiting", ConversationTaskStatus.NEEDS_USER),
            task("running", ConversationTaskStatus.IN_PROGRESS),
            task("queued", ConversationTaskStatus.OPEN),
            task("done", ConversationTaskStatus.DONE),
        )

        val groups = groupTodayTasks(tasks)

        assertEquals(listOf("waiting"), groups.needsUser.map { it.taskId })
        assertEquals(listOf("running", "queued"), groups.active.map { it.taskId })
    }

    private fun task(id: String, status: String) = ConversationTaskEntity(
        taskId = id,
        conversationId = "conversation",
        taskType = "read_only",
        title = id,
        subjectKey = id,
        objective = id,
        status = status,
        requiresUser = status == ConversationTaskStatus.NEEDS_USER,
        capsuleJson = "{}",
        priority = 0,
        createdAt = 1,
        updatedAt = 1,
        expiresAt = null,
    )
}
