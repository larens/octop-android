package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.db.AutomationTaskStatus
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.ws.ServerEvent
import com.octopmobile.app.data.ws.outputText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalLanguageTaskRepositoryTest {
    @Test
    fun instruction_requiresExtractionAndDoesNotGuessMissingDetails() {
        val repository = NaturalLanguageTaskRepository(
            socketFactory = { error("not used") },
            refreshTasks = { ApiResult.Ok(0) },
        )

        val instruction = repository.buildInstruction("每周一早上 9 点整理需要回复的邮件")

        assertTrue(instruction.contains("每周一早上 9 点整理需要回复的邮件"))
        assertTrue(instruction.contains("提炼一个简短标题"))
        assertTrue(instruction.contains("准确提取触发时间"))
        assertTrue(instruction.contains("待办内容"))
        assertTrue(instruction.contains("缺少明确的执行时间或待办内容，不要调用工具"))
        assertTrue(instruction.contains("cronjob_create"))
    }

    @Test
    fun parsesCreatedTaskFromCronToolResult() {
        val repository = NaturalLanguageTaskRepository(
            socketFactory = { error("not used") },
            refreshTasks = { ApiResult.Ok(0) },
        )

        val task = repository.createdTaskFromToolResult(
            """{"id":"cron-1","name":"邮件整理","trigger":"cron:0 9 * * *","prompt":"整理需要回复的邮件"}""",
        )

        assertTrue(task != null)
        assertTrue(task?.name == "邮件整理")
        assertTrue(task?.trigger == "cron:0 9 * * *")
        assertTrue(task?.prompt == "整理需要回复的邮件")
    }

    @Test
    fun recognizesCronToolNameFromNestedServerMessage() {
        val event = ServerEvent.decode(
            Json { ignoreUnknownKeys = true },
            """
            {
              "type":"tool_result",
              "messages":[{
                "name":"cronjob_create",
                "content":"{\"id\":\"cron-1\",\"name\":\"每周阅读总结\",\"trigger\":\"cron:0 20 * * 0\",\"prompt\":\"总结本周阅读\"}"
              }]
            }
            """.trimIndent(),
        ) as ServerEvent.ToolResult

        assertEquals("cronjob_create", event.name)
        assertTrue(event.outputText?.contains("cron-1") == true)
    }

    @Test
    fun modifyInstructionBindsExactTaskAndForbidsCreation() {
        val repository = NaturalLanguageTaskRepository(
            socketFactory = { error("not used") },
            refreshTasks = { ApiResult.Ok(0) },
        )
        val task = task()

        val instruction = repository.buildModifyInstruction(task, "改成每周一 9 点")

        assertTrue(instruction.contains("任务 ID 是：cron-1"))
        assertTrue(instruction.contains("当前触发规则：cron:0 20 * * 0"))
        assertTrue(instruction.contains("用户要求：改成每周一 9 点"))
        assertTrue(instruction.contains("必须使用上面的准确任务 ID"))
        assertTrue(instruction.contains("绝对不要调用 cronjob_create"))
    }

    @Test
    fun acceptsUpdateAndDeleteOnlyForExpectedTask() {
        val repository = NaturalLanguageTaskRepository(
            socketFactory = { error("not used") },
            refreshTasks = { ApiResult.Ok(0) },
        )
        val updated = """{"id":"cron-1","name":"阅读总结","trigger":"cron:0 9 * * 1","prompt":"总结阅读"}"""

        assertEquals(Unit, repository.modificationFromToolResult("cronjob_update", updated, "cron-1"))
        assertEquals(null, repository.modificationFromToolResult("cronjob_update", updated, "cron-2"))
        assertEquals(Unit, repository.modificationFromToolResult("cronjob_delete", """{"deleted":"cron-1"}""", "cron-1"))
        assertEquals(null, repository.modificationFromToolResult("cronjob_create", updated, "cron-1"))
    }

    private fun task() = AutomationTaskEntity(
        id = "cron-1",
        agentId = "agent-1",
        title = "每周阅读总结",
        prompt = "总结本周阅读",
        scheduleRule = "cron:0 20 * * 0",
        scopeRef = null,
        status = AutomationTaskStatus.ACTIVE,
        lastRunAt = null,
        lastStatus = null,
        lastError = null,
        nextRunAt = null,
        createdAt = 0,
    )
}
