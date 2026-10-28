package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.ThreadEntity
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayCardContentTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 27, 18, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun groupsTodayUpcomingAndPastWithoutUnreadBias() {
        val cards = listOf(
            card("yesterday", occurredAt = at(2026, 10, 26, 10, 0)),
            card("evening", occurredAt = at(2026, 10, 27, 19, 30), lifecycle = "read"),
            card("past", occurredAt = at(2026, 10, 27, 10, 0)),
            card("tomorrow", occurredAt = at(2026, 10, 28, 9, 0)),
            card("day-after", occurredAt = at(2026, 10, 29, 9, 0)),
            card("third-day", occurredAt = at(2026, 10, 30, 9, 0)),
            card("outside", occurredAt = at(2026, 10, 31, 9, 0)),
            card("note", kind = "briefing", occurredAt = at(2026, 10, 20, 9, 0)),
        )

        val groups = groupTodayCards(cards, now, zone)

        assertEquals(listOf("evening", "note"), groups.current.map { it.id })
        assertEquals(listOf("tomorrow", "day-after", "third-day"), groups.upcoming.map { it.id })
        assertEquals(listOf("past"), groups.past.map { it.id })
    }

    @Test
    fun summarizesTomorrowAndLaterDaysSeparately() {
        val cards = listOf(
            card("late-tomorrow", occurredAt = at(2026, 10, 28, 15, 30), summary = "10月28日 15:30 · 会议邀请"),
            card("early-tomorrow", occurredAt = at(2026, 10, 28, 9, 0), summary = "10月28日 09:00 · 会议邀请"),
            card("day-after", occurredAt = at(2026, 10, 29, 10, 0), summary = "10月29日 10:00 · 会议邀请"),
            card("third-day", occurredAt = at(2026, 10, 30, 10, 0), summary = "10月30日 10:00 · 会议邀请"),
            card("outside", occurredAt = at(2026, 10, 31, 10, 0), summary = "10月31日 10:00 · 会议邀请"),
        )

        val summary = upcomingCalendarSummary(cards, now, zone)

        assertEquals(listOf("early-tomorrow", "late-tomorrow"), summary.tomorrow.map { it.title })
        assertEquals("09:00", summary.tomorrow.first().time)
        assertEquals(2, summary.laterCount)
    }

    @Test
    fun completedCalendarItemsUseChronologicalOrder() {
        val items = calendarItemSummaries(
            listOf(
                card("afternoon", occurredAt = at(2026, 10, 27, 14, 0), summary = "今天 14:00 · 会议邀请"),
                card("morning", occurredAt = at(2026, 10, 27, 9, 0), summary = "今天 09:00 · 会议邀请"),
            ),
        )

        assertEquals(listOf("morning", "afternoon"), items.map { it.title })
    }

    @Test
    fun mixesRecentUpdatesWithUpcomingMeetingsAndDemotesCompletedTasks() {
        val cards = listOf(
            card(
                "completed-mail",
                kind = "inbox",
                occurredAt = at(2026, 10, 27, 7, 0),
                actionState = "completed",
            ),
            card("afternoon-meeting", occurredAt = at(2026, 10, 27, 13, 30)),
            card("fresh-briefing", kind = "briefing", occurredAt = at(2026, 10, 27, 9, 29)),
            card("morning-meeting", occurredAt = at(2026, 10, 27, 10, 0)),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 27, 9, 30), zone)

        assertEquals(
            listOf("morning-meeting", "fresh-briefing", "afternoon-meeting", "completed-mail"),
            groups.current.map { it.id },
        )

        val later = groupTodayCards(cards, at(2026, 10, 27, 12, 30), zone)
        assertEquals(
            listOf("afternoon-meeting", "fresh-briefing", "completed-mail"),
            later.current.map { it.id },
        )
    }

    @Test
    fun pendingMailStaysVisibleButUrgentMeetingComesFirst() {
        val pendingSummary = """
            【收件概览】
            昨日共收到 3 封新邮件，其中待处理私人邮件 1 封。

            【待处理邮件】
            Taylor Reed：Review request
        """.trimIndent()
        val cards = listOf(
            card(
                "pending-mail",
                kind = "inbox",
                occurredAt = at(2026, 10, 27, 9, 29),
                summary = pendingSummary,
                actionState = "completed",
            ),
            card(
                "normal-mail",
                kind = "inbox",
                occurredAt = at(2026, 10, 27, 9, 29),
                summary = "昨日共收到 2 封新邮件，没有需要回复或跟进的邮件。",
                actionState = "completed",
            ),
            card("urgent-meeting", occurredAt = at(2026, 10, 27, 9, 45)),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 27, 9, 30), zone)

        assertTrue(todayCardHasPendingMail(pendingSummary))
        assertEquals(
            listOf("urgent-meeting", "pending-mail", "normal-mail"),
            groups.current.map { it.id },
        )
    }

    @Test
    fun dailyReviewUsesTodaysConversationsAndNonConversationUpdates() {
        val conversations = todayConversationHighlights(
            listOf(
                thread("yesterday", at(2026, 10, 26, 17, 0)),
                thread("morning", at(2026, 10, 27, 9, 0)),
                thread("latest", at(2026, 10, 27, 17, 30)),
            ),
            now,
            zone,
        )
        val updates = todayUpdateHighlights(
            listOf(
                card(
                    id = "news",
                    kind = "news",
                    occurredAt = at(2026, 10, 27, 16, 0),
                    evidenceJson = """
                        [
                          {"source_type":"web_search","event_type":"web.search_result","occurred_at":1,"privacy_class":"public_web","title":"AI Agent 新进展","url":"https://example.com/a"},
                          {"source_type":"web_search","event_type":"web.search_result","occurred_at":2,"privacy_class":"public_web","title":"云计算容量变化","url":"https://example.com/b"}
                        ]
                    """.trimIndent(),
                ),
                card(
                    id = "conversation",
                    kind = "conversation",
                    occurredAt = at(2026, 10, 27, 17, 0),
                ),
            ),
        )

        assertEquals(listOf("latest", "morning"), conversations.map { it.title })
        assertEquals(listOf("AI Agent 新进展", "云计算容量变化"), updates.map { it.title })
    }

    @Test
    fun dailyReviewHidesInternalThreadIdsAndFallsBackToReadableMessages() {
        val draftId = "draft-b0db105e-364f-4e62-8c9a-ab535f36346c"
        val orphanId = "23bd4744-9e1b-4c08-b93b-dcebf7d142a2"
        val conversations = todayConversationHighlights(
            listOf(
                thread(draftId, at(2026, 10, 27, 17, 30))
                    .copy(lastMessage = "讨论明日发布节奏"),
                thread(orphanId, at(2026, 10, 27, 17, 0))
                    .copy(lastMessage = orphanId),
                thread("readable", at(2026, 10, 27, 16, 0))
                    .copy(lastMessage = orphanId),
            ),
            now,
            zone,
        )

        assertEquals(listOf("讨论明日发布节奏", "readable"), conversations.map { it.title })
        assertTrue(conversations.all { it.detail == null })
    }

    @Test
    fun parsesCalendarSummaryIntoUsefulMeetingFacts() {
        val details = parseCalendarCardSummary("10月29日 17:00 · 会议邀请 · 元中心 7-2-013")

        assertEquals("10月29日", details.day)
        assertEquals("17:00", details.time)
        assertEquals("会议邀请", details.eventType)
        assertEquals("元中心 7-2-013", details.location)
    }

    @Test
    fun deduplicatesEvidenceAndKeepsOnlyReadableContent() {
        val evidence = decodeTodayCardEvidence(
            """
            [
              {"source_type":"web_search","event_type":"web.search_result","occurred_at":1,"privacy_class":"public_web","title":"GPT-6 Astra Pro","excerpt":"并行 agent 交付","url":"https://example.com/a"},
              {"source_type":"web_search","event_type":"web.search_result","occurred_at":2,"privacy_class":"public_web","title":"GPT-6 Astra Pro","excerpt":"重复来源","url":"https://example.com/a"},
              {"source_type":"web_search","event_type":"web.search_result","occurred_at":3,"privacy_class":"public_web","excerpt":"只有摘要也值得保留"}
            ]
            """.trimIndent(),
        )

        val facts = todayCardFacts(evidence)

        assertEquals(2, facts.size)
        assertEquals("GPT-6 Astra Pro", facts[0].title)
        assertTrue(facts[1].title.contains("只有摘要"))
    }

    @Test
    fun newsFactsKeepTheCompleteWebListAndExcludeTheConversation() {
        val items = (1..8).joinToString(",") { index ->
            """
            {"source_type":"web_search","event_type":"web.search_result","occurred_at":$index,"privacy_class":"public_web","title":"News $index","url":"https://example.com/$index"}
            """.trimIndent()
        }
        val evidence = decodeTodayCardEvidence(
            """
            [
              $items,
              {"source_type":"conversation","source_record_id":"thread-1","event_type":"conversation.context","occurred_at":9,"privacy_class":"private_user_content","title":"Cron result"}
            ]
            """.trimIndent(),
        )

        val facts = todayNewsFacts(evidence)

        assertEquals(8, facts.size)
        assertEquals((1..8).map { "News $it" }, facts.map { it.title })
        assertTrue(facts.all { it.url != null })
    }

    @Test
    fun structuredCardSummarySplitsContactAdviceAndRiskSections() {
        val summary = """
            【收件概览】
            昨日共收到 5 封新邮件，其中 2 封直接联系你。

            【处理建议】
            没有需要回复或跟进的事项。

            【其他风险】
            Agent Mail 授权失败，可能遗漏邮件。
        """.trimIndent()

        val sections = parseTodayCardSummary(summary)

        assertEquals(listOf("收件概览", "处理建议", "其他风险"), sections.map { it.heading })
        assertTrue(sections.first().body.startsWith("昨日共收到"))
        assertEquals(sections.first().body, todayCardSummaryPreview(summary))
    }

    @Test
    fun healthCardsRiseInTheirWindowAndDecayLater() {
        val morningSummary = """
            【睡眠概览】
            昨晚睡眠 7 小时 30 分。

            【健康可视化】
            {"w":"morning","m":[{"k":"sleep","v":450,"d":"7 小时 30 分","u":"小时","t":480,"c":"ring"}]}
        """.trimIndent()
        val eveningSummary = """
            【今日概览】
            步数 7,240 步。

            【健康可视化】
            {"w":"evening","m":[{"k":"steps","v":7240,"d":"7,240","u":"步","t":8000,"c":"ring"}]}
        """.trimIndent()

        val early = groupTodayCards(
            listOf(
                card(
                    "morning-health",
                    kind = "health",
                    occurredAt = at(2026, 10, 27, 7, 0),
                    summary = morningSummary,
                ),
                card(
                    "morning-briefing",
                    kind = "briefing",
                    occurredAt = at(2026, 10, 27, 7, 20),
                ),
            ),
            at(2026, 10, 27, 7, 30),
            zone,
        )
        assertEquals(
            listOf("morning-health", "morning-briefing"),
            early.current.map { it.id },
        )

        val late = groupTodayCards(
            listOf(
                card(
                    "morning-health",
                    kind = "health",
                    occurredAt = at(2026, 10, 27, 7, 0),
                    summary = morningSummary,
                ),
                card(
                    "afternoon-briefing",
                    kind = "briefing",
                    occurredAt = at(2026, 10, 27, 15, 20),
                ),
            ),
            at(2026, 10, 27, 15, 30),
            zone,
        )
        assertEquals(
            listOf("afternoon-briefing", "morning-health"),
            late.current.map { it.id },
        )

        val evening = groupTodayCards(
            listOf(
                card(
                    "evening-health",
                    kind = "health",
                    occurredAt = at(2026, 10, 27, 19, 0),
                    summary = eveningSummary,
                ),
                card(
                    "evening-briefing",
                    kind = "briefing",
                    occurredAt = at(2026, 10, 27, 19, 20),
                ),
            ),
            at(2026, 10, 27, 19, 30),
            zone,
        )
        assertEquals(
            listOf("evening-health", "evening-briefing"),
            evening.current.map { it.id },
        )
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun card(
        id: String,
        kind: String = "calendar",
        occurredAt: Long,
        lifecycle: String = "unread",
        summary: String = "summary",
        evidenceJson: String = "[]",
        actionState: String? = null,
    ) = TodayCardEntity(
        id = id,
        agentId = "agent",
        kind = kind,
        title = id,
        summary = summary,
        whyNow = "internal only",
        confidence = 0.9,
        riskLevel = "A2",
        lifecycle = lifecycle,
        occurredAt = occurredAt,
        snoozedUntil = null,
        actionPrompt = "prompt",
        evidenceJson = evidenceJson,
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
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private fun thread(id: String, lastActiveAt: Long) = ThreadEntity(
        threadId = id,
        agentId = "agent",
        title = id,
        lastMessage = "last message",
        pinned = false,
        lastActiveAt = lastActiveAt,
        createdAt = lastActiveAt,
        unreadCount = 0,
        modelRef = null,
        conversationMode = "ask",
        turnActive = false,
        awaitingUser = false,
    )
}
