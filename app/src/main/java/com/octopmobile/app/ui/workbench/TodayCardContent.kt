package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.remote.dto.TodayCardActionState
import com.octopmobile.app.data.remote.dto.TodayEvidenceDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal data class TodayCardGroups(
    val current: List<TodayCardEntity>,
    val upcoming: List<TodayCardEntity>,
    val past: List<TodayCardEntity>,
)

internal fun groupTodayCards(
    cards: List<TodayCardEntity>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): TodayCardGroups {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val upcomingWindowEnd = today.plusDays(3)
    val upcoming = cards
        .filter { card ->
            card.kind == "calendar" &&
                card.localDate(zoneId).let { it.isAfter(today) && !it.isAfter(upcomingWindowEnd) }
        }
        .sortedWith(compareBy(TodayCardEntity::occurredAt, TodayCardEntity::title))
    val past = cards
        .filter { card ->
            card.kind == "calendar" &&
                card.localDate(zoneId) == today &&
                card.occurredAt < nowMillis
        }
        .sortedWith(compareByDescending(TodayCardEntity::occurredAt))
    val outsideWindow = cards.filter { card ->
        card.kind == "calendar" && card.localDate(zoneId).isAfter(upcomingWindowEnd)
    }
    val previousDays = cards.filter { card ->
        card.kind == "calendar" && card.localDate(zoneId).isBefore(today)
    }
    val excluded = upcoming.toSet() + past.toSet() + outsideWindow.toSet() + previousDays.toSet()
    val current = cards
        .filterNot { it in excluded }
        .sortedWith(
            compareByDescending<TodayCardEntity> { it.currentPriority(nowMillis) }
                .thenByDescending(TodayCardEntity::updatedAt)
                .thenBy(TodayCardEntity::title),
        )
    return TodayCardGroups(current = current, upcoming = upcoming, past = past)
}

private fun TodayCardEntity.currentPriority(nowMillis: Long): Int {
    if (kind == "health") return healthPriority(nowMillis)
    val pendingMail = kind == "inbox" && todayCardHasPendingMail(summary)
    val base = if (kind == "calendar") {
        val minutesUntilStart =
            ((occurredAt - nowMillis) / MINUTE_MILLIS).coerceAtLeast(0)
        when {
            minutesUntilStart <= 15 -> 100
            minutesUntilStart <= 30 -> 92
            minutesUntilStart <= 60 -> 84
            minutesUntilStart <= 120 -> 72
            minutesUntilStart <= 240 -> 58
            minutesUntilStart <= 480 -> 42
            minutesUntilStart <= 720 -> 30
            else -> 18
        }
    } else {
        val ageMinutes = ((nowMillis - updatedAt) / MINUTE_MILLIS).coerceAtLeast(0)
        val freshness = when {
            ageMinutes <= 10 -> 88
            ageMinutes <= 30 -> 78
            ageMinutes <= 60 -> 68
            ageMinutes <= 120 -> 54
            ageMinutes <= 240 -> 40
            ageMinutes <= 480 -> 26
            ageMinutes <= 720 -> 16
            else -> 8
        }
        freshness -
            if (actionState == TodayCardActionState.COMPLETED && !pendingMail) 12 else 0
    }
    val needsConfirmation =
        actionState == TodayCardActionState.WAITING_CONFIRMATION ||
            riskLevel == "A3" ||
            riskLevel == "A4"
    return base +
        (if (needsConfirmation) 30 else 0) +
        (if (lifecycle == "unread") 4 else 0) +
        (if (pendingMail) 10 else 0)
}

private fun TodayCardEntity.healthPriority(nowMillis: Long): Int {
    val localNow = Instant.ofEpochMilli(nowMillis)
        .atZone(ZoneId.systemDefault())
    val window = parseHealthVisualization(summary)?.window
    val base = when (window) {
        "morning" -> when (localNow.hour) {
            in 5..8 -> 94
            in 9..11 -> 78
            in 12..14 -> 46
            in 15..17 -> 30
            else -> 12
        }
        "evening" -> when (localNow.hour) {
            in 18..20 -> 94
            in 21..22 -> 78
            23, 0, 1, 2, 3, 4 -> 34
            else -> 12
        }
        "alert" -> if (localNow.hour in 9..17) {
            val ageMinutes = ((nowMillis - updatedAt) / MINUTE_MILLIS).coerceAtLeast(0)
            if (ageMinutes <= 90) 88 else 66
        } else {
            8
        }
        else -> 40
    }
    return base + (if (lifecycle == "unread") 4 else 0)
}

internal data class TodayCalendarItemSummary(
    val title: String,
    val time: String?,
)

internal data class TodayUpcomingCalendarSummary(
    val tomorrow: List<TodayCalendarItemSummary>,
    val laterCount: Int,
)

internal fun calendarItemSummaries(cards: List<TodayCardEntity>): List<TodayCalendarItemSummary> =
    cards.asSequence()
        .filter { it.kind == "calendar" }
        .sortedWith(compareBy(TodayCardEntity::occurredAt, TodayCardEntity::title))
        .map { card ->
            val details = parseCalendarCardSummary(card.summary)
            TodayCalendarItemSummary(title = card.title, time = details.time ?: details.day)
        }
        .toList()

internal fun upcomingCalendarSummary(
    cards: List<TodayCardEntity>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): TodayUpcomingCalendarSummary {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val tomorrow = today.plusDays(1)
    val windowEnd = today.plusDays(3)
    val inWindow = cards.filter { card ->
        card.kind == "calendar" &&
            card.localDate(zoneId).let { it.isAfter(today) && !it.isAfter(windowEnd) }
    }
    return TodayUpcomingCalendarSummary(
        tomorrow = calendarItemSummaries(inWindow.filter { it.localDate(zoneId) == tomorrow }),
        laterCount = inWindow.count { it.localDate(zoneId).isAfter(tomorrow) },
    )
}

internal data class TodayReviewItem(
    val title: String,
    val detail: String?,
    val kind: String,
)

internal fun todayConversationHighlights(
    threads: List<ThreadEntity>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    limit: Int = 3,
): List<TodayReviewItem> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    return threads.asSequence()
        .filter { thread ->
            Instant.ofEpochMilli(thread.lastActiveAt).atZone(zoneId).toLocalDate() == today
        }
        .sortedByDescending(ThreadEntity::lastActiveAt)
        .mapNotNull { thread ->
            val message = thread.lastMessage
                ?.compactSnippet(80)
                ?.takeUnless(String::isInternalThreadId)
            val title = thread.title
                ?.compactSnippet(80)
                ?.takeUnless(String::isInternalThreadId)
                ?: message?.takeUnless(String::isInternalThreadId)
                ?: return@mapNotNull null
            TodayReviewItem(
                title = title,
                detail = message?.takeIf { it != title },
                kind = "conversation",
            )
        }
        .take(limit)
        .toList()
}

internal fun todayUpdateHighlights(
    cards: List<TodayCardEntity>,
    limit: Int = 4,
): List<TodayReviewItem> =
    cards.asSequence()
        .filter { it.kind != "calendar" && it.kind != "conversation" && it.kind != "health" }
        .flatMap { card ->
            val facts = todayCardFacts(decodeTodayCardEvidence(card.evidenceJson), limit = 2)
            if (facts.isEmpty()) {
                sequenceOf(
                    TodayReviewItem(
                        title = card.title,
                        detail = card.summary.compactSnippet(),
                        kind = card.kind,
                    ),
                )
            } else {
                facts.asSequence().map { fact ->
                    TodayReviewItem(
                        title = fact.title,
                        detail = fact.excerpt?.compactSnippet(),
                        kind = card.kind,
                    )
                }
            }
        }
        .distinctBy { it.title.lowercase() }
        .take(limit)
        .toList()

internal data class CalendarCardDetails(
    val day: String,
    val time: String?,
    val eventType: String?,
    val location: String?,
)

internal data class TodayCardSummarySection(
    val heading: String?,
    val body: String,
)

internal fun todayCardHasPendingMail(summary: String): Boolean =
    parseTodayCardSummary(summary).any { it.heading == "待处理邮件" }

internal fun parseTodayCardSummary(summary: String): List<TodayCardSummarySection> {
    val normalized = summary.trim()
    if (normalized.isEmpty()) return emptyList()
    val headings = SummaryHeadingPattern.findAll(normalized).toList()
    if (headings.isEmpty()) {
        return listOf(TodayCardSummarySection(heading = null, body = normalized))
    }
    return buildList {
        val preface = normalized.substring(0, headings.first().range.first).trim()
        if (preface.isNotEmpty()) add(TodayCardSummarySection(heading = null, body = preface))
        headings.forEachIndexed { index, match ->
            val bodyStart = match.range.last + 1
            val bodyEnd = headings.getOrNull(index + 1)?.range?.first ?: normalized.length
            add(
                TodayCardSummarySection(
                    heading = match.groupValues[1].trim(),
                    body = normalized.substring(bodyStart, bodyEnd).trim(),
                ),
            )
        }
    }
}

internal fun todayCardSummaryPreview(summary: String): String {
    val sections = parseTodayCardSummary(summary)
        .filterNot { it.heading == "健康可视化" }
    val preferredHeadings = setOf(
        "收件概览",
        "联系概览",
        "睡眠概览",
        "昨晚睡眠",
        "今日概览",
        "需要留意",
    )
    return sections.firstOrNull { it.heading in preferredHeadings }?.body
        ?: sections.firstOrNull()?.body
        ?: summary.trim()
}

internal fun parseCalendarCardSummary(summary: String): CalendarCardDetails {
    val parts = summary.split("·").map(String::trim).filter(String::isNotEmpty)
    val schedule = parts.firstOrNull().orEmpty()
    val splitAt = schedule.indexOf(' ')
    val day: String
    val time: String?
    if (splitAt > 0) {
        day = schedule.substring(0, splitAt).trim()
        time = schedule.substring(splitAt + 1).trim().takeIf(String::isNotBlank)
    } else {
        day = schedule
        time = null
    }
    return CalendarCardDetails(
        day = day,
        time = time,
        eventType = parts.getOrNull(1),
        location = parts.drop(2).joinToString(" · ").takeIf(String::isNotBlank),
    )
}

internal data class TodayCardFact(
    val title: String,
    val excerpt: String?,
    val sourceType: String,
    val sourceRecordId: String?,
    val url: String?,
)

internal fun decodeTodayCardEvidence(evidenceJson: String): List<TodayEvidenceDto> =
    runCatching {
        TodayEvidenceJson.decodeFromString(
            ListSerializer(TodayEvidenceDto.serializer()),
            evidenceJson,
        )
    }.getOrDefault(emptyList())

internal fun todayCardFacts(evidence: List<TodayEvidenceDto>, limit: Int = 3): List<TodayCardFact> {
    val seen = mutableSetOf<String>()
    return evidence.asSequence()
        .mapNotNull { item ->
            val title = item.title?.trim()
                ?: item.excerpt?.trim()?.take(80)
                ?: return@mapNotNull null
            val key = item.url?.trim()?.takeIf(String::isNotBlank)
                ?: title.lowercase()
            if (!seen.add(key)) return@mapNotNull null
            TodayCardFact(
                title = title,
                excerpt = item.excerpt?.trim()?.takeIf { it.isNotBlank() && it != title },
                sourceType = item.sourceType,
                sourceRecordId = item.sourceRecordId,
                url = item.url?.trim()?.takeIf(String::isNotBlank),
            )
        }
        .take(limit)
        .toList()
}

internal fun todayNewsFacts(evidence: List<TodayEvidenceDto>): List<TodayCardFact> =
    todayCardFacts(evidence, limit = Int.MAX_VALUE)
        .filter { it.url != null || it.sourceType == "web" || it.sourceType == "web_search" }

private fun TodayCardEntity.localDate(zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(occurredAt).atZone(zoneId).toLocalDate()

private fun String.compactSnippet(maxLength: Int = 100): String =
    replace(Regex("\\s+"), " ").trim().take(maxLength)

private fun String.isInternalThreadId(): Boolean =
    trim().matches(InternalThreadIdPattern)

private val InternalThreadIdPattern =
    Regex("(?i)^(?:draft-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val SummaryHeadingPattern = Regex("(?m)^【([^】]+)】\\s*$")

private const val MINUTE_MILLIS = 60_000L

private val TodayEvidenceJson = Json { ignoreUnknownKeys = true }
