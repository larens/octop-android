package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.db.ConversationEventEntity
import com.octopmobile.app.data.db.ConversationTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenUiRulesTest {

    @Test
    fun `long finished assistant messages offer the full detail view`() {
        assertTrue(
            shouldOfferMessageDetails(
                isUser = false,
                text = "主动消息".repeat(100),
                streaming = false,
            ),
        )
    }

    @Test
    fun `short streaming and user messages stay inline`() {
        assertFalse(
            shouldOfferMessageDetails(
                isUser = false,
                text = "简短回复",
                streaming = false,
            ),
        )
        assertFalse(
            shouldOfferMessageDetails(
                isUser = false,
                text = "主动消息".repeat(100),
                streaming = true,
            ),
        )
        assertFalse(
            shouldOfferMessageDetails(
                isUser = true,
                text = "主动消息".repeat(100),
                streaming = false,
            ),
        )
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

    @Test
    fun `dispatch plan children are only rendered by the plan card`() {
        val now = 2_000_000L
        val planPayload = """
            {
              "plan_id":"plan-1",
              "items":[
                {"plan_item_id":"item-1","child_task_id":"worker-1"}
              ]
            }
        """.trimIndent()
        val planEvent = event(
            taskId = null,
            seq = 10,
            type = "plan.progress",
            payloadJson = planPayload,
        )
        val tasks = listOf(
            task(
                id = "worker-1",
                type = "read_only",
                status = "needs_user",
                updatedAt = now - 1_000,
            ),
            task(
                id = "worker-2",
                type = "read_only",
                status = "in_progress",
                updatedAt = now - 500,
            ),
        )
        val events = listOf(
            planEvent,
            event(
                taskId = "worker-1",
                seq = 11,
                type = "worker.failed",
                summary = "Budget exhausted",
            ),
            event(taskId = "worker-1", seq = 12, type = "worker.progress"),
            event(taskId = "worker-2", seq = 13, type = "worker.started"),
        )

        assertEquals(
            listOf("worker-2"),
            projectActiveRouterTasks(tasks, events).map { it.taskId },
        )
        assertEquals(
            null,
            projectLatestRouterTaskResult(tasks, events, nowMillis = now),
        )
    }

    @Test
    fun `dispatch plan is projected from the latest full plan event`() {
        val payload = """
            {
              "plan_id":"plan-1",
              "conversation_id":"conversation-1",
              "trace_id":"trace-plan",
              "objective":"Compare two cities",
              "status":"awaiting_approval",
              "version":1,
              "plan_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "estimated_tokens":192000,
              "hard_token_limit":192000,
              "max_concurrency":2,
              "budget_extension_count":0,
              "budget_extended_tokens":0,
              "budget_request":{
                "request_id":"budget-request-1",
                "plan_item_id":"item-1",
                "item_key":"weather",
                "title":"Read forecasts",
                "status":"awaiting_approval",
                "reason":"Worker reached its token budget",
                "used_tokens":128000,
                "old_token_limit":128000,
                "proposed_token_limit":192000,
                "requested_tokens":64000,
                "requested_cost_micros":32000,
                "cost_per_1k_tokens_micros":500,
                "remaining_user_tokens":31000000,
                "remaining_agent_tokens":35000000,
                "remaining_global_tokens":39000000,
                "remaining_worker_surface_tokens":1800000,
                "can_continue":true,
                "max_extensions":1,
                "created_at":102
              },
              "created_at":100,
              "proposed_at":100,
              "budget_preview":{
                "max_concurrency":2,
                "max_attempts_per_worker":2,
                "user_remaining_tokens":32000000,
                "agent_remaining_tokens":36000000,
                "global_remaining_tokens":40000000
              },
              "items":[
                {
                  "plan_item_id":"item-1",
                  "key":"weather",
                  "title":"Read forecasts",
                  "objective":"Read two forecasts",
                  "status":"ready",
                  "token_limit":128000
                }
              ]
            }
        """.trimIndent()
        val plan = projectDispatchPlan(
            events = listOf(
                event(
                    taskId = null,
                    seq = 8,
                    type = "plan.proposed",
                    payloadJson = payload,
                ),
            ),
            nowSeconds = 101,
        )

        assertEquals("plan-1", plan?.planId)
        assertEquals("awaiting_approval", plan?.status)
        assertEquals(2, plan?.budgetPreview?.maxAttemptsPerWorker)
        assertEquals(32_000_000L, plan?.budgetPreview?.userRemainingTokens)
        assertEquals(1, plan?.items?.size)
        assertEquals(128_000L, plan?.items?.first()?.tokenLimit)
        assertEquals("budget-request-1", plan?.budgetRequest?.requestId)
        assertEquals(64_000L, plan?.budgetRequest?.requestedTokens)
        assertEquals(32_000L, plan?.budgetRequest?.requestedCostMicros)
        assertTrue(plan?.budgetRequest?.canContinue == true)
    }

    @Test
    fun `parallel task approvals stay visible until each task is decided`() {
        val first = approvalPayload(
            approvalId = "approval-1",
            taskId = "task-1",
            status = "pending",
            expiresAt = 2_000,
        )
        val second = approvalPayload(
            approvalId = "approval-2",
            taskId = "task-2",
            status = "pending",
            expiresAt = 2_000,
        )
        val rejected = approvalPayload(
            approvalId = "approval-3",
            taskId = "task-3",
            status = "rejected",
            expiresAt = 2_000,
        )
        val expired = approvalPayload(
            approvalId = "approval-4",
            taskId = "task-4",
            status = "pending",
            expiresAt = 900,
        )

        val projected = projectTaskApprovals(
            events = listOf(
                event(
                    taskId = "task-1",
                    seq = 1,
                    type = "task.approval_required",
                    payloadJson = first,
                ),
                event(
                    taskId = "task-2",
                    seq = 2,
                    type = "task.approval_required",
                    payloadJson = second,
                ),
                event(
                    taskId = "task-3",
                    seq = 3,
                    type = "task.approval_rejected",
                    payloadJson = rejected,
                ),
                event(
                    taskId = "task-4",
                    seq = 4,
                    type = "task.approval_required",
                    payloadJson = expired,
                ),
            ),
            nowSeconds = 1_000,
        )

        assertEquals(
            listOf("approval-2", "approval-1"),
            projected.map { it.approvalId },
        )
    }

    private fun approvalPayload(
        approvalId: String,
        taskId: String,
        status: String,
        expiresAt: Long,
    ): String = """
        {
          "approval_id":"$approvalId",
          "conversation_id":"conversation-1",
          "task_id":"$taskId",
          "trace_id":"trace-$taskId",
          "task_title":"Send a message",
          "task_objective":"Send the exact approved content",
          "task_status":"needs_user",
          "action_name":"message.send",
          "action":{"to":"owner@example.com"},
          "action_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "idempotency_key":"idem-$approvalId",
          "status":"$status",
          "version":1,
          "token_limit":8000,
          "expires_at":$expiresAt,
          "created_at":100,
          "updated_at":100
        }
    """.trimIndent()

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
        taskId: String?,
        seq: Long,
        type: String,
        summary: String = type,
        payloadJson: String? = null,
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
        payloadJson = payloadJson,
        usageJson = "{}",
        createdAt = seq,
    )
}
