package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.ThreadEntity
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                          {"source_type":"web_search","event_type":"web.search_result","occurred_at":2,"privacy_class":"public_web","title":"云计算容量变化","url":"https://example.com/b"},
                          {"source_type":"conversation","source_record_id":"thread-1","event_type":"conversation.context","occurred_at":3,"privacy_class":"private_user_content","title":"定时任务结果","excerpt":"完整执行记录保留在会话中。"}
                        ]
                    """.trimIndent(),
                ),
                card(
                    id = "conversation",
                    kind = "conversation",
                    occurredAt = at(2026, 10, 27, 17, 0),
                ),
            ),
            now,
            zone,
        )

        assertEquals(listOf("latest", "morning"), conversations.map { it.title })
        assertEquals(listOf("AI Agent 新进展", "云计算容量变化"), updates.map { it.title })
    }

    @Test
    fun hidesLegacyConversationFollowUpCards() {
        val groups = groupTodayCards(
            listOf(
                card(
                    "legacy-conversation",
                    kind = "conversation",
                    occurredAt = at(2026, 10, 27, 17, 0),
                ),
                card(
                    "current-signal",
                    kind = "signal",
                    occurredAt = at(2026, 10, 27, 17, 30),
                ),
            ),
            now,
            zone,
        )

        assertEquals(listOf("current-signal"), groups.current.map { it.id })
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
    fun dailyReviewReplacesBareUrlThreadTitlesWithTheLatestReadableMessage() {
        val conversations = todayConversationHighlights(
            listOf(
                thread("url-title", at(2026, 10, 27, 17, 30)).copy(
                    title = "https://www.doubao.com/thread/xXhwdgBIV...",
                    lastMessage = "已确认连接器恢复，并整理了后续动作。",
                ),
            ),
            now,
            zone,
        )

        assertEquals(
            listOf("已确认连接器恢复，并整理了后续动作。"),
            conversations.map { it.title },
        )
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
    fun parsesTodayCalendarRangeAndLabeledLocation() {
        val details = parseCalendarCardSummary(
            "16:00-17:00 · 会议邀请 · 地点：元中心 7-2-013",
        )

        assertEquals("", details.day)
        assertEquals("16:00-17:00", details.time)
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
    fun hidesSessionOnlyExecutionEvidenceButKeepsUsefulConversationContext() {
        val evidence = decodeTodayCardEvidence(
            """
            [
              {"source_type":"conversation","source_record_id":"thread-1","event_type":"conversation.context","occurred_at":1,"privacy_class":"private_user_content","title":"定时任务结果","excerpt":"完整执行记录、过程与来源保留在会话中。"},
              {"source_type":"conversation","source_record_id":"thread-2","event_type":"conversation.context","occurred_at":2,"privacy_class":"private_user_content","title":"讨论结论","excerpt":"本周先验证主会话链路。"}
            ]
            """.trimIndent(),
        )

        val facts = todayCardFacts(evidence)

        assertEquals(listOf("讨论结论"), facts.map { it.title })
    }

    @Test
    fun sourceOnlyEvidenceTitlesFallBackToTheArticleHost() {
        val evidence = decodeTodayCardEvidence(
            """
            [
              {"source_type":"web_search","event_type":"web.search_result","occurred_at":1,"privacy_class":"public_web","title":"来源：；","url":"https://view.inews.qq.com/a/20261030A04MS300"},
              {"source_type":"web_search","event_type":"web.search_result","occurred_at":2,"privacy_class":"public_web","title":"来源： ； · view.inews.qq.com","url":"https://view.inews.qq.com/a/20261030A01FDS00"},
              {"source_type":"web_search","event_type":"web.search_result","occurred_at":3,"privacy_class":"public_web","title":"来源：；"}
            ]
            """.trimIndent(),
        )

        val facts = todayCardFacts(evidence)

        assertEquals(listOf("view.inews.qq.com", "view.inews.qq.com"), facts.map { it.title })
        assertEquals(
            listOf(
                "https://view.inews.qq.com/a/20261030A04MS300",
                "https://view.inews.qq.com/a/20261030A01FDS00",
            ),
            facts.map { it.url },
        )
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

    @Test
    fun morningHealthDropsBelowTheNewMorningFeedAfterItsWindow() {
        val morningSummary = """
            【睡眠概览】
            昨晚睡眠 7 小时 30 分。

            【健康可视化】
            {"w":"morning","m":[{"k":"sleep","v":450,"d":"7 小时 30 分","u":"小时","t":480,"c":"ring"}]}
        """.trimIndent()
        val cards = listOf(
            card(
                "sleep",
                kind = "health",
                occurredAt = at(2026, 10, 27, 7, 54),
                summary = morningSummary,
            ),
            card(
                "morning-briefing",
                kind = "briefing",
                occurredAt = at(2026, 10, 27, 8, 0),
            ),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 27, 8, 30), zone)

        assertEquals(
            listOf("morning-briefing", "sleep"),
            groups.current.map { it.id },
        )

        val afternoon = groupTodayCards(cards, at(2026, 10, 27, 13, 30), zone)
        assertEquals(
            listOf("morning-briefing", "sleep"),
            afternoon.current.map { it.id },
        )
    }

    @Test
    fun previousDayHealthMovesToHistoryAndCannotRiseAgainTonight() {
        val summary = """
            【今日概览】
            步数 7,240 步。

            【健康可视化】
            {"w":"evening","m":[{"k":"steps","v":7240,"d":"7,240","u":"步","t":8000,"c":"ring"}]}
        """.trimIndent()
        val cards = listOf(
            card(
                "yesterday-health",
                kind = "health",
                occurredAt = at(2026, 10, 27, 19, 0),
                summary = summary,
            ),
            card(
                "today-signal",
                kind = "signal",
                occurredAt = at(2026, 10, 28, 14, 0),
            ),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 28, 19, 30), zone)

        assertEquals(listOf("today-signal"), groups.current.map { it.id })
        assertEquals(listOf("yesterday-health"), groups.history.map { it.id })
    }

    @Test
    fun todayEveningHealthHidesPreviousEveningCardsButKeepsMorningHistory() {
        val cards = listOf(
            card(
                "yesterday-evening",
                kind = "health",
                occurredAt = at(2026, 10, 27, 19, 0),
                summary = healthSummary("evening"),
            ),
            card(
                "yesterday-morning",
                kind = "health",
                occurredAt = at(2026, 10, 27, 7, 30),
                summary = healthSummary("morning"),
            ),
            card(
                "today-evening",
                kind = "health",
                occurredAt = at(2026, 10, 28, 19, 0),
                summary = healthSummary("evening"),
            ),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 28, 20, 0), zone)

        assertEquals(listOf("today-evening"), groups.current.map { it.id })
        assertEquals(listOf("yesterday-morning"), groups.history.map { it.id })
    }

    @Test
    fun previousEveningHealthStaysVisibleUntilTodayEveningHealthArrives() {
        val cards = listOf(
            card(
                "yesterday-evening",
                kind = "health",
                occurredAt = at(2026, 10, 27, 19, 0),
                summary = healthSummary("evening"),
            ),
            card(
                "today-morning",
                kind = "health",
                occurredAt = at(2026, 10, 28, 7, 30),
                summary = healthSummary("morning"),
            ),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 28, 20, 0), zone)

        assertEquals(listOf("yesterday-evening"), groups.history.map { it.id })
    }

    @Test
    fun todayEveningHealthAlsoHidesLegacyCardsIdentifiedByTitle() {
        val cards = listOf(
            card(
                "legacy-evening",
                kind = "health",
                occurredAt = at(2026, 10, 27, 19, 0),
            ).copy(title = "晚间健康 · 12,379 步"),
            card(
                "today-evening",
                kind = "health",
                occurredAt = at(2026, 10, 28, 19, 0),
                summary = healthSummary("evening"),
            ),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 28, 20, 0), zone)

        assertTrue(groups.history.isEmpty())
    }

    @Test
    fun sameDayHealthKeepsOnlyTheLatestCardInEachTimeWindow() {
        val cards = listOf(
            card(
                "old-morning",
                kind = "health",
                occurredAt = at(2026, 10, 28, 7, 0),
                summary = healthSummary("morning"),
            ),
            card(
                "new-morning",
                kind = "health",
                occurredAt = at(2026, 10, 28, 8, 0),
                summary = healthSummary("morning"),
            ),
            card(
                "old-evening",
                kind = "health",
                occurredAt = at(2026, 10, 28, 18, 0),
                summary = healthSummary("evening"),
            ),
            card(
                "new-evening",
                kind = "health",
                occurredAt = at(2026, 10, 28, 19, 0),
                summary = healthSummary("evening"),
            ),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 28, 20, 0), zone)

        assertEquals(listOf("new-evening", "new-morning"), groups.current.map { it.id })
        assertTrue(groups.history.isEmpty())
    }

    @Test
    fun newMorningHealthHidesPreviousMorningButKeepsUnreplacedEveningHistory() {
        val cards = listOf(
            card(
                "yesterday-morning",
                kind = "health",
                occurredAt = at(2026, 10, 27, 7, 30),
                summary = healthSummary("morning"),
            ),
            card(
                "yesterday-evening",
                kind = "health",
                occurredAt = at(2026, 10, 27, 19, 0),
                summary = healthSummary("evening"),
            ),
            card(
                "today-morning",
                kind = "health",
                occurredAt = at(2026, 10, 28, 7, 30),
                summary = healthSummary("morning"),
            ),
        )

        val groups = groupTodayCards(cards, at(2026, 10, 28, 9, 0), zone)

        assertEquals(listOf("today-morning"), groups.current.map { it.id })
        assertEquals(listOf("yesterday-evening"), groups.history.map { it.id })
    }

    @Test
    fun dailyReviewRollsToYesterdayBeforeFive() {
        val beforeCutoff = todayReviewPeriod(at(2026, 10, 28, 4, 59), zone)
        val afterCutoff = todayReviewPeriod(at(2026, 10, 28, 5, 0), zone)

        assertEquals(LocalDateTime.of(2026, 10, 27, 0, 0).toLocalDate(), beforeCutoff.date)
        assertTrue(beforeCutoff.isYesterday)
        assertEquals(LocalDateTime.of(2026, 10, 28, 0, 0).toLocalDate(), afterCutoff.date)
        assertFalse(afterCutoff.isYesterday)
    }

    @Test
    fun dailyReviewDoesNotBorrowYesterdayItemsDuringToday() {
        val cards = listOf(
            card(
                "yesterday-meeting",
                occurredAt = at(2026, 10, 27, 10, 0),
                summary = "10月27日 10:00 · 会议邀请",
            ),
            card(
                "today-meeting",
                occurredAt = at(2026, 10, 28, 10, 0),
                summary = "10月28日 10:00 · 会议邀请",
            ),
            card(
                "yesterday-signal",
                kind = "signal",
                occurredAt = at(2026, 10, 27, 14, 0),
            ),
            card(
                "today-signal",
                kind = "signal",
                occurredAt = at(2026, 10, 28, 14, 0),
            ),
        )

        val earlyUpdates = todayUpdateHighlights(cards, at(2026, 10, 28, 2, 0), zone)
        val daytimeUpdates = todayUpdateHighlights(cards, at(2026, 10, 28, 13, 0), zone)
        val earlySchedules = completedScheduleHighlights(cards, at(2026, 10, 28, 2, 0), zone)
        val daytimeSchedules = completedScheduleHighlights(cards, at(2026, 10, 28, 13, 0), zone)

        assertEquals(listOf("yesterday-signal"), earlyUpdates.map { it.title })
        assertEquals(listOf("today-signal"), daytimeUpdates.map { it.title })
        assertEquals(listOf("yesterday-meeting"), earlySchedules.map { it.title })
        assertEquals(listOf("today-meeting"), daytimeSchedules.map { it.title })
    }

    @Test
    fun dailyPlanPutsScheduleBeforeWeatherAndDoesNotMasqueradeAsNews() {
        val weatherCard = card(
            "daily-plan",
            kind = "briefing",
            occurredAt = at(2026, 10, 27, 7, 30),
            summary = """
                【今日安排】
                09:30 产品评审，需要准备原型。

                【天气提醒】
                北京，小雨，降雨概率 60%，带伞。

                【行动建议】
                提前十分钟出门。
            """.trimIndent(),
        ).copy(title = "晨间天气与日程")

        val details = requireNotNull(parseTodayDailyPlan(weatherCard))

        assertEquals("今日安排与建议", details.title)
        assertEquals("09:30 产品评审，需要准备原型。", details.focus)
        assertEquals("天气提醒", details.weatherHeading)
        assertTrue(details.weatherIsRainy)
        assertEquals("北京", details.weatherDetails?.location)
        assertEquals("小雨", details.weatherDetails?.condition)
        assertEquals(null, details.weatherDetails?.temperature)
        assertFalse(weatherCard.isNewsRecommendationCard())
        assertEquals(1f, dailyWeatherVisualWeight(weatherCard.updatedAt, weatherCard.updatedAt))
        assertEquals(
            0.42f,
            dailyWeatherVisualWeight(
                weatherCard.updatedAt,
                weatherCard.updatedAt + 15 * 60 * 60 * 1000L,
            ),
        )
        assertFalse(weatherIndicatesRain("北京，晴，降雨概率 0%，不用带伞。"))
        assertTrue(weatherIndicatesRain("北京，小雨，降雨概率 60%。"))
        assertEquals(null, normalizeDailyPlanLine("| — | 暂无数据 | — | 飞书日历未接通 |"))
        assertEquals(null, normalizeDailyPlanLine("|---|---|---|"))
    }

    @Test
    fun weatherSummaryHighlightsLocationConditionAndTemperature() {
        val summary = parseDailyWeather("北京，晴转小雨，6~15°C，降雨概率 60%。早晚偏冷。")

        assertEquals("北京", summary?.location)
        assertEquals("晴转小雨", summary?.condition)
        assertEquals("6~15°C", summary?.temperature)
        assertEquals("降雨概率 60%。早晚偏冷。", summary?.detail)
        assertTrue(summary?.isStructured == true)
    }

    @Test
    fun weatherSummaryUnderstandsLabeledWeatherOutput() {
        val summary = parseDailyWeather(
            """
                查询城市：北京。今天是 2026-10-29 周四
                日期：2026-10-29（周四） · 当前实况 晴
                11.2°C
                降雨概率 0%。
            """.trimIndent(),
        )

        assertEquals("北京", summary?.location)
        assertEquals("晴", summary?.condition)
        assertEquals("11.2°C", summary?.temperature)
        assertEquals("降雨概率 0%。", summary?.detail)
        assertTrue(summary?.isStructured == true)
    }

    @Test
    fun dailyPlanRemovesInternalProcessAndFallsBackToMultiCityWeatherText() {
        val plan = card(
            "daily-plan",
            kind = "briefing",
            occurredAt = at(2026, 10, 31, 22, 18),
            summary = """
                【明日安排】
                明日上午在滨海，晚上返回北京。
                位置判断：按你的规则今天在天津滨海，明天回北京。
                飞书日程：连接器权限没接上，返回 No events in this time range。

                | 时间 | 安排 | 备注 |
                |---|---|---|
                | 20:00 之后 | 滨海 → 北京南 | 订 20:00 后车次 |

                【天气与穿着】
                查询城市：天津（滨海用天津市区代查）——来源：彩云天气 MCP（实时 + 逐小时 + 3 日预报）。
                - 滨海：明天多云转晴，降雨概率 0%，最低 8.4°C / 最高 17.4°C。
                - 北京：明天多云，降雨概率 0%，最低 5.3°C / 最高 18.2°C。
                穿着：早晚偏凉，带薄外套。

                【今晚准备】
                把折叠伞放进书包。
            """.trimIndent(),
        ).copy(title = "明日安排与准备")

        val details = requireNotNull(parseTodayDailyPlan(plan))

        assertTrue(details.focus.contains("明日上午在滨海，晚上返回北京"))
        assertFalse(details.focus.contains("位置判断"))
        assertFalse(details.focus.contains("飞书日程"))
        assertTrue(details.weather.orEmpty().contains("滨海：明天多云转晴"))
        assertTrue(details.weather.orEmpty().contains("北京：明天多云"))
        assertFalse(details.weather.orEmpty().contains("MCP"))
        assertEquals(null, details.weatherDetails)
        assertTrue(details.advice.orEmpty().contains("把折叠伞放进书包"))
    }

    @Test
    fun multiCityWeatherBecomesCompactCityCardsWithUsefulNotes() {
        val plan = card(
            "multi-city-weather",
            kind = "briefing",
            occurredAt = at(2026, 10, 31, 22, 18),
            summary = """
                【明日安排】
                明天白天在滨海，晚上回北京。

                【天气与穿着】
                - 滨海：明天 11-01：天气 多云转晴（上午多云，15:00 前后转晴），降雨概率 0%，最低 8.4°C / 最高 17.4°C
                - 与今天对比（今天 10-31 为 6.97~14.08°C、中雨、降雨概率 70%）：最高 升 3.3°C，最低 升 1.5°C → 不是降温，是回升。
                - 今晚实时（22:18）：11.2°C，体感 9.2°C，正在下大雨；逐小时 23:00 转阴、此后到明早降雨概率 0%。
                - 北京：你明晚到京；明天 5.3~18.2°C，多云，降雨概率 0%；夜里 21 点后约 10°C，站台上要穿外套
                - 穿着：明早 8.4°C 偏凉，属于早晚偏凉；薄外套够，不用上厚羽绒。晚上到北京 10~11°C，外套随身别装箱底

                【今晚准备】
                把折叠伞放进书包。
            """.trimIndent(),
        ).copy(title = "明日安排与准备")

        val details = requireNotNull(parseTodayDailyPlan(plan))
        val weather = requireNotNull(parseMultiCityWeather(requireNotNull(details.weather)))

        assertEquals(listOf("滨海", "北京"), weather.cities.map { it.city })
        assertEquals("多云转晴", weather.cities[0].condition)
        assertEquals("8.4–17.4°C", weather.cities[0].temperature)
        assertEquals("降雨 0%", weather.cities[0].precipitation)
        assertTrue(weather.cities[0].note.orEmpty().contains("今晚有雨转停"))
        assertTrue(weather.cities[0].note.orEmpty().contains("较今天回升"))
        assertEquals("多云", weather.cities[1].condition)
        assertEquals("5.3~18.2°C", weather.cities[1].temperature)
        assertEquals("到京后约 10°C，备外套", weather.cities[1].note)
        assertEquals(
            "早晚偏凉，薄外套即可；返京随身带",
            weather.clothing,
        )
    }

    @Test
    fun liveTomorrowWeatherLineBecomesStructuredMetricsInsteadOfRawText() {
        val plan = card(
            "live-weather",
            kind = "briefing",
            occurredAt = at(2026, 11, 1, 21, 30),
            summary = """
                【明日安排】
                暂无已同步的日程安排。

                【天气与穿着】
                - 北京（明日全天）：晴 ☀️，降雨概率 0%，最低 4.7°C / 最高 17.6°C
                - 对比今天（5.8°C / 17.6°C）：最高温持平，最低低约 1.1°C
                - 穿衣：薄外套或风衣 + 长裤，早出加条围巾
                - 带伞结论：不用带伞，全天 0% 降雨

                【今晚准备】
                明早的外套和围巾放在玄关。
            """.trimIndent(),
        ).copy(title = "明日安排与准备")

        val details = requireNotNull(parseTodayDailyPlan(plan))

        assertEquals("北京", details.weatherDetails?.location)
        assertEquals("晴", details.weatherDetails?.condition)
        assertEquals("4.7–17.6°C", details.weatherDetails?.temperature)
        assertTrue(details.weatherDetails?.isStructured == true)
        assertTrue(details.weatherDetails?.detail.orEmpty().contains("降雨概率 0%"))
        assertTrue(details.weatherDetails?.detail.orEmpty().contains("不用带伞"))
    }

    @Test
    fun readingReviewPromotesCountsAndBooksBeforeTheAnalysis() {
        val reading = card(
            "weekly-reading",
            kind = "review",
            occurredAt = at(2026, 11, 1, 20, 0),
            summary = """
                【阅读概览】
                本周阅读 2 本 · 新增划线 0 条 · 书架 1201 本
                最近划线：2026-08-07《AI未来已来》

                【本周在读】
                10-31《Token经济》· 0 条
                10-29《故事：材质、结构、风格和银幕剧作的原理》· 0 条

                【核心观点】
                - Token 只是 AI 投入中最小的一部分。
            """.trimIndent(),
        ).copy(title = "每周阅读总结")

        val summary = requireNotNull(parseTodayReadingSummary(reading))

        assertEquals(
            listOf("本周阅读" to "2", "新增划线" to "0", "书架" to "1201"),
            summary.metrics.map { it.label to it.value },
        )
        assertEquals(2, summary.books.size)
        assertEquals("Token经济", summary.books[0].title)
        assertEquals("0 条", summary.books[0].marks)
        assertEquals(listOf("核心观点"), summary.remainingSections.map { it.heading })
    }

    @Test
    fun reconciliationCardOffersTaskSpecificInputAndBuildsACompleteReply() {
        val reconciliation = card(
            "reconciliation",
            kind = "task",
            occurredAt = at(2026, 11, 1, 19, 30),
            summary = "现在账面上电子现金约 467 元。两件事想跟你确认一下：实际扣款金额和刷卡次数。",
            actionState = "completed",
        ).copy(
            title = "周日银通卡对账",
            executionThreadId = "thread-reconciliation",
        )
        val compactLive = reconciliation.copy(
            summary = "现在账面上电子现金约 467 元，折合还能坐 7 趟单程。",
        )

        val spec = requireNotNull(todayQuickReplySpec(reconciliation))
        val compactSpec = requireNotNull(todayQuickReplySpec(compactLive))
        val message = buildReconciliationReply(
            taskTitle = reconciliation.title,
            amount = "133",
            swipes = "2",
            note = "两趟都刷了银通卡",
        )

        assertEquals(TodayQuickReplyKind.RECONCILIATION, spec.kind)
        assertEquals(TodayQuickReplyKind.RECONCILIATION, compactSpec.kind)
        assertTrue(compactSpec.request.contains("账单金额"))
        assertTrue(spec.request.contains("想跟你确认"))
        assertTrue(message.contains("账单金额：133 元"))
        assertTrue(message.contains("刷卡次数：2 次"))
        assertTrue(message.contains("两趟都刷了银通卡"))
        assertTrue(message.contains("更新台账"))
    }

    @Test
    fun scheduledTaskKeepsAReplyEntryEvenWhenItsCardSummaryIsCompact() {
        val task = card(
            "weekly-plan",
            kind = "task",
            occurredAt = at(2026, 11, 1, 19, 30),
            summary = "需要你提供本周想优先推进的三件事。",
        ).copy(executionThreadId = "thread-weekly")
        val plain = card(
            "plain",
            kind = "task",
            occurredAt = at(2026, 11, 1, 19, 30),
            summary = "任务已完成。",
        ).copy(executionThreadId = "thread-plain")
        val noThread = card(
            "no-thread",
            kind = "task",
            occurredAt = at(2026, 11, 1, 19, 30),
            summary = "需要你提供本周想优先推进的三件事。",
        )

        assertEquals(TodayQuickReplyKind.GENERIC, todayQuickReplySpec(task)?.kind)
        assertEquals(TodayQuickReplyKind.GENERIC, todayQuickReplySpec(plain)?.kind)
        assertEquals(null, todayQuickReplySpec(noThread))
    }

    @Test
    fun newsFactTitlesUseTheArticleTitleWithoutSourceTimeOrTableNumber() {
        val facts = todayNewsFacts(
            decodeTodayCardEvidence(
                """
                    [
                      {"source_type":"web_search","event_type":"web.search_result","occurred_at":1,"privacy_class":"public_web","title":"| S1 | 新华网·五中全会公报 | |","url":"https://www.news.cn/example"},
                      {"source_type":"web_search","event_type":"web.search_result","occurred_at":2,"privacy_class":"public_web","title":"「站点直读」10-31 14:26","url":"https://www.ithome.com/example"}
                    ]
                """.trimIndent(),
            ),
        )

        assertEquals("新华网·五中全会公报", facts[0].title)
        assertEquals("ithome.com", facts[1].title)
    }

    @Test
    fun newsFactTitlesRejectConnectorUiJsonAndKeepTheGitHubRepositoryName() {
        val facts = todayNewsFacts(
            decodeTodayCardEvidence(
                """
                    [
                      {
                        "source_type":"web_search",
                        "event_type":"web.search_result",
                        "occurred_at":1,
                        "privacy_class":"public_web",
                        "title":"{\"octop_ui\": {\"renderer\": \"github_trending_list\", \"version\": 1}, \"data\": {\"items\": [",
                        "url":"https://github.com/heydamir/unslop"
                      }
                    ]
                """.trimIndent(),
            ),
        )

        assertEquals("heydamir/unslop", facts.single().title)
    }

    @Test
    fun dailyPlanFallsBackToPhoneCalendarWhenFeishuScheduleIsUnavailable() {
        val plan = card(
            "daily-plan",
            kind = "briefing",
            occurredAt = at(2026, 10, 27, 7, 30),
            summary = """
                【今日安排】
                飞书日程未获取到，暂无已同步的日程安排。

                【天气提醒】
                北京，晴，8~18°C。
            """.trimIndent(),
        ).copy(title = "晨间天气与日程")
        val phoneCards = listOf(
            card(
                "review",
                occurredAt = at(2026, 10, 27, 9, 30),
                summary = "今天 09:30 · 会议邀请",
            ).copy(title = "产品评审"),
            card(
                "tomorrow-review",
                occurredAt = at(2026, 10, 28, 9, 30),
                summary = "明天 09:30 · 会议邀请",
            ).copy(title = "明日评审"),
        )

        val fallback = phoneScheduleFallbackLines(
            cards = phoneCards,
            nowMillis = now,
            isTomorrow = false,
            zoneId = zone,
        )
        val details = requireNotNull(parseTodayDailyPlan(plan))
            .withPhoneScheduleFallback(fallback)

        assertEquals(listOf("09:30 产品评审"), fallback)
        assertEquals("09:30 产品评审", details.focus)
        assertEquals("今日安排 · 手机日程", details.focusHeading)
    }

    @Test
    fun industrySignalParsesTheSharedJudgmentAndComparedEvidence() {
        val signal = card(
            "industry-signal",
            kind = "signal",
            occurredAt = at(2026, 10, 27, 14, 0),
            summary = """
                【总判断】
                形态已经清楚了，数字还只有发布方口径。

                【关键信号】
                1. OpenAI｜GPT-6 Astra Pro
                成熟度：可验证进展
                证据强度：3/3
                核心变化：多个 agent 并行推进同一任务，过程实时可见。
                不确定性：Pro 档仍在灰度。
                对我的意义：今天就能对照现有流程试一次。
                2. Anthropic｜企业交易 agent
                成熟度：仅发布方口径
                证据强度：1/3
                核心变化：agent 从聊天走向替企业做交易决策。
                不确定性：没有第三方验证和收费口径。
                对我的意义：先观望。

                【行动建议】
                先验证已经开放的能力，交易类进展等第三方证据。
            """.trimIndent(),
        )

        val details = requireNotNull(parseTodaySignal(signal))

        assertEquals("形态已经清楚了，数字还只有发布方口径。", details.judgment)
        assertEquals(2, details.signals.size)
        assertEquals("OpenAI", details.signals[0].company)
        assertEquals("GPT-6 Astra Pro", details.signals[0].subject)
        assertEquals("可验证进展", details.signals[0].maturity)
        assertEquals(3, details.signals[0].evidenceStrength)
        assertTrue(details.signals[0].change.contains("并行推进"))
        assertEquals("先观望。", details.signals[1].relevance)
        assertEquals("先验证已经开放的能力，交易类进展等第三方证据。", details.advice)
        assertTrue(signal.isNewsRecommendationCard())
        assertFalse(signal.supportsScheduleReminder())
    }

    @Test
    fun industrySignalMaturityUsesAShortDisplayLabel() {
        assertEquals(
            "第三方验证",
            simplifySignalMaturity("已有第三方验证（SEC 文件+路透社报道）"),
        )
        assertEquals("厂商口径", simplifySignalMaturity("仅发布方口径"))

        val signal = card(
            "industry-signal",
            kind = "signal",
            occurredAt = at(2026, 10, 27, 14, 0),
            summary = """
                【关键信号】
                1. Anthropic｜企业交易 agent
                成熟度：已有第三方验证（SEC 文件+路透社报道）
                证据强度：3/3
                核心变化：企业交易 agent 进入可验证阶段。
            """.trimIndent(),
        )

        assertEquals("第三方验证", parseTodaySignal(signal)?.signals?.first()?.maturity)
    }

    @Test
    fun industrySignalDoesNotBorrowTheDailyPlanOrGenericNewsPresentation() {
        val signal = card(
            "industry-signal",
            kind = "signal",
            occurredAt = at(2026, 10, 27, 14, 0),
            summary = "【关键信号】1. Example｜Model\n核心变化：A verified capability shipped.",
        )

        assertFalse(signal.isDailyPlanCard())
        assertEquals(null, parseTodayDailyPlan(signal))
        assertEquals(null, parseTodaySignal(signal.copy(kind = "news")))
    }

    @Test
    fun cardActionsFollowScheduleAndNewsSemantics() {
        assertTrue("calendar".supportsScheduleReminder())
        assertTrue("anniversary".supportsScheduleReminder())
        assertFalse("briefing".supportsScheduleReminder())

        val anniversary = card(
            "anniversary",
            kind = "inbox",
            occurredAt = at(2026, 10, 27, 9, 0),
        ).copy(title = "纪念日提醒（统一入口）")
        assertTrue(anniversary.supportsScheduleReminder())

        val newsBriefing = card(
            "news",
            kind = "briefing",
            occurredAt = at(2026, 10, 27, 8, 0),
            summary = "• Example release changed an API contract.",
        ).copy(title = "每日新闻")
        assertTrue(newsBriefing.isNewsRecommendationCard())
    }

    @Test
    fun failedCronPlaceholdersAreNeverRenderedAsTodayInformation() {
        val groups = groupTodayCards(
            listOf(
                card(
                    "failed-weather",
                    kind = "briefing",
                    occurredAt = at(2026, 10, 27, 7, 30),
                    summary = "本次执行失败：服务器暂时不可用",
                    actionState = "failed",
                ),
                card(
                    "valid-weather",
                    kind = "briefing",
                    occurredAt = at(2026, 10, 27, 7, 0),
                    summary = "【今日安排】09:30 产品评审",
                    actionState = "completed",
                ),
            ),
            at(2026, 10, 27, 9, 0),
            zone,
        )

        assertEquals(listOf("valid-weather"), groups.current.map { it.id })
    }

    @Test
    fun conversationFollowUpIsHiddenWhenAnySourceContainsAFailureMessage() {
        val groups = groupTodayCards(
            listOf(
                card(
                    "broken-conversations",
                    kind = "conversation",
                    occurredAt = at(2026, 10, 27, 8, 0),
                    evidenceJson = """
                        [
                          {"source_type":"conversation","source_record_id":"thread-1","event_type":"conversation.context","occurred_at":1,"privacy_class":"private_user_content","title":"正常会话","excerpt":"已经形成明确结论，下一步继续验证。"},
                          {"source_type":"conversation","source_record_id":"thread-2","event_type":"conversation.context","occurred_at":2,"privacy_class":"private_user_content","title":"失败会话","excerpt":"模型调用失败：file is not a database"}
                        ]
                    """.trimIndent(),
                ),
                card(
                    "valid-briefing",
                    kind = "briefing",
                    occurredAt = at(2026, 10, 27, 7, 0),
                    summary = "【今日安排】09:30 产品评审",
                ),
            ),
            at(2026, 10, 27, 9, 0),
            zone,
        )

        assertEquals(listOf("valid-briefing"), groups.current.map { it.id })
    }

    @Test
    fun didiRideCardParsesVehicleTripAndEtaWithoutPhone() {
        val ride = card(
            "ride",
            kind = "task",
            occurredAt = at(2026, 11, 1, 18, 20),
            summary = """
                【车辆信息】
                车牌号：京A·12345
                车型：白色比亚迪秦 PLUS
                司机：张师傅

                【行程】
                状态：司机已接单
                距接驾点：1.2 公里 · 预计 3 分钟到达
                望京 SOHO → 首都机场 T3
                订单号：ORDER-123
            """.trimIndent(),
        )

        val details = requireNotNull(parseTodayRide(ride))

        assertEquals("司机已接单", details.status)
        assertEquals("京A·12345", details.plate)
        assertEquals("白色比亚迪秦 PLUS", details.carModel)
        assertEquals("张师傅", details.driver)
        assertEquals("1.2 公里", details.distance)
        assertEquals("3 分钟", details.eta)
        assertEquals("望京 SOHO → 首都机场 T3", details.route)
        assertEquals("ORDER-123", details.orderId)
    }

    @Test
    fun matchingRideCardStillRendersBeforeAVehicleIsAssigned() {
        val details = requireNotNull(
            parseTodayRide(
                card(
                    "matching-ride",
                    kind = "task",
                    occurredAt = at(2026, 11, 1, 18, 20),
                    summary = """
                        【车辆信息】
                        正在为你匹配车辆。

                        【行程】
                        状态：正在匹配司机
                        订单号：ORDER-123
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("正在匹配司机", details.status)
        assertEquals(null, details.plate)
        assertEquals("ORDER-123", details.orderId)
    }

    @Test
    fun coffeePickupCardUsesOnlyARealOrderDetailCode() {
        val coffee = card(
            "coffee",
            kind = "task",
            occurredAt = at(2026, 11, 2, 8, 43),
            summary = """
                【取餐信息】
                取餐码：972
                取餐二维码：aufckp8XAAk=
                门店：元中心快手总部店
                预计可取：08:53

                【订单】
                状态：精心制作中
                饮品：精萃澳瑞白
                实付：¥10.90
                订单号：7703368209932943369
            """.trimIndent(),
        ).copy(executionThreadId = "thread-coffee")

        val details = requireNotNull(parseTodayCoffee(coffee))

        assertEquals("972", details.pickupCode)
        assertEquals("aufckp8XAAk=", details.qrPayload)
        assertEquals("精心制作中", details.status)
        assertEquals("元中心快手总部店", details.shopName)
        assertEquals("精萃澳瑞白", details.productName)
        assertEquals("¥10.90", details.amount)
        assertEquals("08:53", details.readyAt)
        assertEquals("7703368209932943369", details.orderId)
        assertEquals(null, todayQuickReplySpec(coffee))
        assertEquals(
            null,
            parseTodayCoffee(
                coffee.copy(
                    summary = coffee.summary.replace(
                        "取餐二维码：aufckp8XAAk=",
                        "取餐二维码：972",
                    ),
                ),
            )?.qrPayload,
        )
        assertEquals(
            null,
            parseTodayCoffee(
                coffee.copy(
                    summary = coffee.summary.replace("取餐码：972", "取餐码：生成中"),
                ),
            ),
        )
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun healthSummary(window: String): String {
        val metric = if (window == "morning") {
            """{"k":"sleep","v":450,"d":"7 小时 30 分","u":"小时","t":480,"c":"ring"}"""
        } else {
            """{"k":"steps","v":7240,"d":"7,240","u":"步","t":8000,"c":"ring"}"""
        }
        return """
            【健康概览】
            今日状态平稳。

            【健康可视化】
            {"w":"$window","m":[$metric]}
        """.trimIndent()
    }

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
