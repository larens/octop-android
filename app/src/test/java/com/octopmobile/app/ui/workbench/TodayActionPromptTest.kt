package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayActionPromptTest {
    @Test
    fun `health action includes the card record and strips visualization json`() {
        val prompt = healthCard().actionPromptForChat()

        assertTrue(prompt.contains("卡片记录："))
        assertTrue(prompt.contains("步数：4,224 步"))
        assertTrue(prompt.contains("healthfit-cn"))
        assertFalse(prompt.contains("【健康可视化】"))
        assertFalse(prompt.contains("\"w\":\"evening\""))
    }

    @Test
    fun `already contextualized health prompt is not duplicated`() {
        val marker = "卡片记录：\n步数：4,224 步\n\n"
        val prompt = healthCard(actionPrompt = "${marker}请给建议。").actionPromptForChat()

        assertEquals("${marker}请给建议。", prompt)
    }

    @Test
    fun `other cards keep their action prompt unchanged`() {
        val prompt = healthCard(
            kind = "calendar",
            actionPrompt = "请帮我查看安排。",
        ).actionPromptForChat()

        assertEquals("请帮我查看安排。", prompt)
    }

    private fun healthCard(
        kind: String = "health",
        actionPrompt: String = "请使用 healthfit-cn 给我保守、可执行的生活建议。",
    ) = TodayCardEntity(
        id = "card",
        agentId = "agent",
        kind = kind,
        title = "晚间健康 · 4,224 步",
        summary = "【今日概览】\n• 步数：4,224 步\n\n【健康可视化】\n{\"w\":\"evening\"}",
        whyNow = "晚间回顾",
        confidence = 1.0,
        riskLevel = "A2",
        lifecycle = "unread",
        occurredAt = 0,
        snoozedUntil = null,
        actionPrompt = actionPrompt,
        evidenceJson = "[]",
        evidenceCount = 0,
        evidenceSources = "",
        sourceUrl = null,
        goalId = null,
        goalTitle = null,
        goalNeedsReview = false,
        executionThreadId = null,
        executionTurnId = null,
        actionState = null,
        actionError = null,
        actionUpdatedAt = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
