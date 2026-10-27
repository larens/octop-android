package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.ThreadEntity
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
    val excluded = upcoming.toSet() + past.toSet() + outsideWindow.toSet()
    val current = cards
        .filterNot { it in excluded }
        .sortedWith(
            compareBy<TodayCardEntity>(
                { if (it.kind == "calendar") 0 else 1 },
                { if (it.kind == "calendar") it.occurredAt else Long.MAX_VALUE },
                { -it.updatedAt },
            ),
        )
    return TodayCardGroups(current = current, upcoming = upcoming, past = past)
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
        .filter { it.kind != "calendar" && it.kind != "conversation" }
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

private val TodayEvidenceJson = Json { ignoreUnknownKeys = true }
