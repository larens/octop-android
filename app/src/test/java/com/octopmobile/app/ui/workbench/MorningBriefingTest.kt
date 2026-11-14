package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.remote.dto.TodayCardActionState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MorningBriefingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 11, 14, 9, 30)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun mergesSameMorningBriefingAndInboxCardsIntoSections() {
        val cards = listOf(
            card("schedule", kind = "briefing", title = "晨间今日安排与建议", hour = 7, minute = 30),
            card("news", kind = "briefing", title = "每日新闻与关注领域简报", hour = 8, minute = 10),
            card(
                "tomorrow-plan",
                kind = "briefing",
                title = "明日安排与准备",
                hour = 21,
                minute = 0,
                day = 13,
            ),
            card(
                "mail",
                kind = "inbox",
                title = "每日 9:00 昨日邮件汇总",
                hour = 9,
                minute = 0,
                summary = "【收件概览】\n2 封未读\n【待处理邮件】\n客户回复",
            ),
            card("health", kind = "health", title = "昨晚睡眠", hour = 7, minute = 0),
            card("meeting", kind = "calendar", title = "产品评审", hour = 10, minute = 0),
            card("yesterday", kind = "briefing", title = "昨日新闻", hour = 8, minute = 0, day = 13),
        )

        val digest = buildMorningDigest(cards, now, zone)!!

        assertEquals(setOf("tomorrow-plan", "schedule", "news", "mail"), digest.cardIds)
        assertEquals(4, digest.itemCount)
        assertEquals(
            listOf("必须处理", "可代拟", "可延后"),
            digest.sections.map { it.titleRes }.map(::sectionTitle),
        )
        assertEquals("mail", digest.sections[0].items.single().card.id)
        assertEquals(
            listOf("tomorrow-plan", "schedule"),
            digest.sections[1].items.map { it.card.id },
        )
        assertEquals("news", digest.sections[2].items.single().card.id)
    }

    @Test
    fun keepsAfternoonAndSingleMorningCardsSeparate() {
        val one = listOf(
            card("morning", kind = "briefing", title = "晨间简报", hour = 8, minute = 0),
        )
        assertNull(buildMorningDigest(one, now, zone))

        val afternoon = listOf(
            card("a", kind = "briefing", title = "新闻 A", hour = 14, minute = 0),
            card("b", kind = "briefing", title = "新闻 B", hour = 15, minute = 0),
        )
        assertNull(buildMorningDigest(afternoon, now, zone))
    }

    @Test
    fun treatsWaitingConfirmationAsMustHandle() {
        val cards = listOf(
            card(
                "approval",
                kind = "briefing",
                title = "需要确认的行程",
                hour = 8,
                minute = 0,
                actionState = TodayCardActionState.WAITING_CONFIRMATION,
            ),
            card("news", kind = "briefing", title = "行业新闻", hour = 8, minute = 30),
        )

        val digest = buildMorningDigest(cards, now, zone)!!

        assertEquals("approval", digest.sections.first().items.single().card.id)
    }

    private fun sectionTitle(resId: Int): String = when (resId) {
        com.octopmobile.app.R.string.today_digest_must_do -> "必须处理"
        com.octopmobile.app.R.string.today_digest_can_draft -> "可代拟"
        else -> "可延后"
    }

    private fun card(
        id: String,
        kind: String,
        title: String,
        hour: Int,
        minute: Int,
        day: Int = 14,
        summary: String = "今日概览",
        actionState: String? = null,
    ) = TodayCardEntity(
        id = id,
        agentId = "agent",
        kind = kind,
        title = title,
        summary = summary,
        whyNow = "",
        confidence = 1.0,
        riskLevel = "A1",
        lifecycle = "unread",
        occurredAt = LocalDateTime.of(2026, 11, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli(),
        snoozedUntil = null,
        actionPrompt = "",
        evidenceJson = "[]",
        evidenceCount = 0,
        evidenceSources = "",
        sourceUrl = null,
        goalId = null,
        goalTitle = null,
        goalNeedsReview = false,
        executionThreadId = null,
        executionTurnId = null,
        actionState = actionState,
        actionError = null,
        actionUpdatedAt = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
