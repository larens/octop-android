package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.db.AutomationTaskStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationTaskGroupsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-04T00:00:00Z").toEpochMilli()

    @Test
    fun groupsActiveTasksByNextRunAndKeepsPausedSeparate() {
        val tasks = listOf(
            task("today", at("2026-11-04T10:00:00+08:00")),
            task("week", at("2026-11-09T10:00:00+08:00")),
            task("later", at("2026-11-20T10:00:00+08:00")),
            task("paused", null, AutomationTaskStatus.PAUSED),
        )

        val groups = groupAutomationTasks(tasks, now, zone)

        assertEquals(
            listOf(
                AutomationTaskBucket.TODAY,
                AutomationTaskBucket.NEXT_SEVEN_DAYS,
                AutomationTaskBucket.LATER,
                AutomationTaskBucket.PAUSED,
            ),
            groups.map(AutomationTaskGroup::bucket),
        )
        assertEquals(listOf("today"), groups[0].tasks.map { it.id })
        assertEquals(listOf("week"), groups[1].tasks.map { it.id })
        assertEquals(listOf("later"), groups[2].tasks.map { it.id })
        assertEquals(listOf("paused"), groups[3].tasks.map { it.id })
    }

    private fun at(value: String): Long =
        OffsetDateTime.parse(value).toInstant().toEpochMilli()

    private fun task(
        id: String,
        nextRunAt: Long?,
        status: String = AutomationTaskStatus.ACTIVE,
    ) = AutomationTaskEntity(
        id = id,
        agentId = "agent",
        title = id,
        prompt = id,
        scheduleRule = "cron:0 10 * * *",
        scopeRef = null,
        status = status,
        lastRunAt = null,
        lastStatus = null,
        lastError = null,
        nextRunAt = nextRunAt,
        createdAt = 1,
    )
}
