package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.remote.dto.TodayCardActionState
import com.octopmobile.app.data.remote.dto.TodayEvidenceDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal data class TodayCardGroups(
    val current: List<TodayCardEntity>,
    val upcoming: List<TodayCardEntity>,
    val past: List<TodayCardEntity>,
    val history: List<TodayCardEntity>,
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
    val history = cards
        .filter { card ->
            card.kind == "health" && card.localDate(zoneId).isBefore(today)
        }
        .sortedWith(
            compareByDescending<TodayCardEntity>(TodayCardEntity::occurredAt)
                .thenByDescending(TodayCardEntity::updatedAt),
        )
    val excluded = upcoming.toSet() +
        past.toSet() +
        outsideWindow.toSet() +
        previousDays.toSet() +
        history.toSet()
    val current = cards
        .filterNot { it in excluded }
        .sortedWith(
            compareByDescending<TodayCardEntity> { it.currentPriority(nowMillis, zoneId) }
                .thenByDescending(TodayCardEntity::updatedAt)
                .thenBy(TodayCardEntity::title),
        )
    return TodayCardGroups(
        current = current,
        upcoming = upcoming,
        past = past,
        history = history,
    )
}

private fun TodayCardEntity.currentPriority(nowMillis: Long, zoneId: ZoneId): Int {
    if (kind == "health") return healthPriority(nowMillis, zoneId)
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

private fun TodayCardEntity.healthPriority(nowMillis: Long, zoneId: ZoneId): Int {
    val localNow = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    if (localDate(zoneId).isBefore(localNow.toLocalDate())) {
        // A previous day's health card must never regain its old visualization window.
        return -100
    }
    val window = parseHealthVisualization(summary)?.window
    val ageMinutes = ((nowMillis - occurredAt) / MINUTE_MILLIS).coerceAtLeast(0)
    val base = when (window) {
        "morning" -> when (localNow.hour) {
            in 5..6 -> 96
            7 -> if (ageMinutes <= 75) 94 else 76
            8 -> if (ageMinutes <= 75) 70 else 56
            9 -> 10
            10 -> 6
            11 -> 4
            in 12..17 -> 2
            else -> 0
        }
        "evening" -> when (localNow.hour) {
            18 -> if (ageMinutes <= 90) 94 else 78
            19 -> 94
            20 -> 82
            21 -> 62
            22 -> 44
            23, 0, 1, 2, 3, 4 -> 14
            else -> 8
        }
        "alert" -> if (localNow.hour in 9..17) {
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

internal fun completedScheduleHighlights(
    cards: List<TodayCardEntity>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<TodayCalendarItemSummary> {
    val reviewDate = todayReviewPeriod(nowMillis, zoneId).date
    return calendarItemSummaries(
        cards.filter { card ->
            card.kind == "calendar" &&
                card.localDate(zoneId) == reviewDate &&
                card.occurredAt < nowMillis
        },
    )
}

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

internal const val TODAY_REVIEW_CUTOFF_HOUR = 5

internal data class TodayReviewPeriod(
    val date: LocalDate,
    val isYesterday: Boolean,
)

internal fun todayReviewPeriod(
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): TodayReviewPeriod {
    val localNow = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val date = if (localNow.hour < TODAY_REVIEW_CUTOFF_HOUR) {
        localNow.toLocalDate().minusDays(1)
    } else {
        localNow.toLocalDate()
    }
    return TodayReviewPeriod(
        date = date,
        isYesterday = date != localNow.toLocalDate(),
    )
}

internal fun todayConversationHighlights(
    threads: List<ThreadEntity>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    limit: Int = 3,
    reviewDate: LocalDate = todayReviewPeriod(nowMillis, zoneId).date,
): List<TodayReviewItem> {
    return threads.asSequence()
        .filter { thread ->
            Instant.ofEpochMilli(thread.lastActiveAt).atZone(zoneId).toLocalDate() == reviewDate
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
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    limit: Int = 4,
): List<TodayReviewItem> {
    val reviewDate = todayReviewPeriod(nowMillis, zoneId).date
    return cards.asSequence()
        .filter { it.localDate(zoneId) == reviewDate }
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
}

internal data class CalendarCardDetails(
    val day: String,
    val time: String?,
    val eventType: String?,
    val location: String?,
)

internal data class TodayWeatherDetails(
    val location: String?,
    val condition: String?,
    val temperature: String?,
    val detail: String?,
) {
    val isStructured: Boolean
        get() = location != null && condition != null && temperature != null
}

internal data class TodayDailyPlanDetails(
    val title: String,
    val focusHeading: String,
    val focus: String,
    val weatherHeading: String?,
    val weather: String?,
    val weatherDetails: TodayWeatherDetails?,
    val adviceHeading: String?,
    val advice: String?,
    val isTomorrow: Boolean,
    val weatherIsRainy: Boolean,
)

internal data class TodayIndustrySignal(
    val company: String,
    val subject: String?,
    val maturity: String?,
    val evidenceStrength: Int?,
    val change: String,
    val uncertainty: String?,
    val relevance: String?,
)

internal data class TodaySignalDetails(
    val judgment: String,
    val signals: List<TodayIndustrySignal>,
    val advice: String?,
)

internal fun String.supportsScheduleReminder(): Boolean =
    this == "calendar" || this == "anniversary"

internal fun TodayCardEntity.supportsScheduleReminder(): Boolean =
    kind.supportsScheduleReminder() || title.contains("纪念日")

internal fun TodayCardEntity.isDailyPlanCard(): Boolean {
    if (kind != "briefing") return false
    val headings = parseTodayCardSummary(summary).mapNotNull { it.heading }.toSet()
    return headings.any { it in DailyPlanHeadings } ||
        title.contains("天气与日程") ||
        title.contains("天气与明日准备") ||
        title == "今日安排与建议" ||
        title == "明日安排与准备"
}

internal fun TodayCardEntity.isNewsRecommendationCard(): Boolean = when (kind) {
    "news", "signal" -> true
    "briefing" -> !isDailyPlanCard()
    else -> false
}

internal fun parseTodaySignal(card: TodayCardEntity): TodaySignalDetails? {
    if (card.kind != "signal") return null
    val sections = parseTodayCardSummary(card.summary)
    val signals = parseSignalItems(
        sectionBody(sections, "关键信号", "行业信号", "值得关注的变化").orEmpty(),
    )
    if (signals.isEmpty()) return null
    return TodaySignalDetails(
        judgment = sectionBody(sections, "总判断", "核心判断", "今日判断").orEmpty(),
        signals = signals,
        advice = sectionBody(sections, "行动建议", "今天可以做什么"),
    )
}

internal fun parseTodayDailyPlan(card: TodayCardEntity): TodayDailyPlanDetails? {
    if (!card.isDailyPlanCard()) return null
    val sections = parseTodayCardSummary(card.summary)
    val isTomorrow = card.title.contains("明日") ||
        card.summary.contains("【明日安排】") ||
        card.summary.contains("【明日天气】") ||
        card.summary.contains("【天气与穿着】") ||
        card.summary.contains("【今晚准备】")
    val focus = sectionBody(
        sections,
        "今日安排",
        "明日安排",
        "今日重点",
        "明日重点",
        "日程与出行",
        "日程与准备",
        "明日准备",
        "今日日程",
        "明日日程",
    ) ?: sections.firstOrNull { it.heading == null }?.body
        ?: "暂无已同步的日程安排。"
    val weather = sectionBody(
        sections,
        "天气提醒",
        "天气与穿着",
        "天气",
        "今日天气",
        "明日天气",
    )
    val advice = sectionBody(
        sections,
        "行动建议",
        "今晚准备",
        "处理建议",
        "晨间建议",
        "夜间建议",
        "出行建议",
    )
    return TodayDailyPlanDetails(
        title = if (isTomorrow) "明日安排与准备" else "今日安排与建议",
        focusHeading = if (isTomorrow) "明日安排" else "今日安排",
        focus = focus,
        weatherHeading = weather?.let { if (isTomorrow) "天气与穿着" else "天气提醒" },
        weather = weather,
        weatherDetails = weather?.let(::parseDailyWeather),
        adviceHeading = advice?.let { if (isTomorrow) "今晚准备" else "行动建议" },
        advice = advice,
        isTomorrow = isTomorrow,
        weatherIsRainy = weather?.let(::weatherIndicatesRain) ?: false,
    )
}

private fun parseSignalItems(body: String): List<TodayIndustrySignal> {
    val items = mutableListOf<TodayIndustrySignal>()
    var current: SignalItemBuilder? = null

    fun flush() {
        current?.takeIf { it.company.isNotBlank() && it.change.isNotBlank() }?.let {
            items += TodayIndustrySignal(
                company = it.company,
                subject = it.subject,
                maturity = it.maturity,
                evidenceStrength = it.evidenceStrength,
                change = it.change,
                uncertainty = it.uncertainty,
                relevance = it.relevance,
            )
        }
        current = null
    }

    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isBlank()) return@forEach
        val item = SignalItemPattern.matchEntire(line)
        if (item != null) {
            flush()
            val name = item.groupValues[1].trim()
            val parts = name.split(SignalNameSeparator, limit = 2)
            current = SignalItemBuilder(
                company = parts.first().trim(),
                subject = parts.getOrNull(1)?.trim()?.takeIf(String::isNotBlank),
            )
            return@forEach
        }
        val builder = current ?: return@forEach
        val fields = SignalFieldPattern.findAll(line).toList()
        if (fields.isNotEmpty()) {
            fields.forEachIndexed { index, match ->
                val value = line
                    .substring(match.range.last + 1, fields.getOrNull(index + 1)?.range?.first ?: line.length)
                    .trim()
                    .trim('｜', '|', ';', '；')
                when (match.groupValues[1]) {
                    "成熟度" -> builder.maturity = value
                        .takeIf(String::isNotBlank)
                        ?.let(::simplifySignalMaturity)
                    "证据强度" -> builder.evidenceStrength = SignalEvidenceLevelPattern
                        .find(value)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    "核心变化" -> builder.change = value
                    "不确定性" -> builder.uncertainty = value.takeIf(String::isNotBlank)
                    "对我的意义" -> builder.relevance = value.takeIf(String::isNotBlank)
                }
            }
        } else if (builder.change.isBlank()) {
            builder.change = line
        }
    }
    flush()
    return items.take(3)
}

internal fun simplifySignalMaturity(value: String): String {
    val withoutDetail = value
        .replace(Regex("[（(][^）)]*[）)]"), "")
        .trim()
    return when {
        "第三方" in withoutDetail -> "第三方验证"
        "仅发布" in withoutDetail || "厂商" in withoutDetail -> "厂商口径"
        else -> withoutDetail
    }
}

internal fun parseDailyWeather(value: String): TodayWeatherDetails? {
    val labeledLocation = WeatherLocationLabelPattern.find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val labeledCondition = WeatherConditionLabelPattern.find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(WeatherTemperaturePattern, "")
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val labeledTemperature = WeatherTemperaturePattern.find(value)?.value
    if (labeledLocation != null || labeledCondition != null) {
        return TodayWeatherDetails(
            location = labeledLocation,
            condition = labeledCondition,
            temperature = labeledTemperature,
            detail = WeatherProbabilityDetailPattern.find(value)?.value?.trim(),
        )
    }

    val coarseSegments = value.lineSequence()
        .flatMap { it.split(WeatherSegmentSeparator).asSequence() }
        .map { segment ->
            segment
                .trim()
                .trimStart('-', '•', '*')
                .trim()
                .replace(WeatherHeadingPrefix, "")
                .replace(WeatherDayPrefix, "")
                .trim()
        }
        .filter(String::isNotBlank)
        .toList()
    val segments = if (coarseSegments.size == 1) {
        coarseSegments.first().split(Regex("\\s+")).filter(String::isNotBlank)
    } else {
        coarseSegments
    }
    if (segments.isEmpty()) return null

    val temperatureIndex = segments.indexOfFirst {
        WeatherTemperaturePattern.containsMatchIn(it)
    }
    val temperature = segments.getOrNull(temperatureIndex)
        ?.let { WeatherTemperaturePattern.find(it)?.value }
    val conditionIndex = segments.indices.firstOrNull { index ->
        index != temperatureIndex &&
            index != 0 &&
            !WeatherDetailSegmentPattern.containsMatchIn(segments[index]) &&
            WeatherConditionWords.any(segments[index]::contains)
    }
    val condition = conditionIndex?.let(segments::getOrNull)
    val locationIndex = segments.indices.firstOrNull { index ->
        index != temperatureIndex &&
            index != conditionIndex &&
            index == 0 &&
            !WeatherDetailSegmentPattern.containsMatchIn(segments[index]) &&
            !WeatherConditionWords.any(segments[index]::contains)
    }
    val location = locationIndex?.let(segments::getOrNull)
    val consumed = setOfNotNull(temperatureIndex.takeIf { it >= 0 }, conditionIndex, locationIndex)
    val detail = segments
        .filterIndexed { index, _ -> index !in consumed }
        .joinToString("，")
        .takeIf(String::isNotBlank)

    if (location == null && condition == null && temperature == null) return null
    return TodayWeatherDetails(
        location = location,
        condition = condition,
        temperature = temperature,
        detail = detail,
    )
}

internal fun phoneScheduleFallbackLines(
    cards: List<TodayCardEntity>,
    nowMillis: Long,
    isTomorrow: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<String> {
    val targetDate = Instant.ofEpochMilli(nowMillis)
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(if (isTomorrow) 1 else 0)
    return cards.asSequence()
        .filter { it.kind == "calendar" && it.localDate(zoneId) == targetDate }
        .sortedBy(TodayCardEntity::occurredAt)
        .map { card ->
            val parsedTime = parseCalendarCardSummary(card.summary).time
                ?.takeIf(ClockTimePattern::matches)
            val time = parsedTime ?: Instant.ofEpochMilli(card.occurredAt)
                .atZone(zoneId)
                .format(ClockTimeFormatter)
            "$time ${card.title.trim()}".trim()
        }
        .filter { it.isNotBlank() }
        .distinct()
        .take(6)
        .toList()
}

internal fun TodayDailyPlanDetails.withPhoneScheduleFallback(
    lines: List<String>,
): TodayDailyPlanDetails {
    if (lines.isEmpty() || !needsPhoneScheduleFallback()) return this
    return copy(
        focusHeading = "${if (isTomorrow) "明日安排" else "今日安排"} · 手机日程",
        focus = lines.joinToString("\n"),
    )
}

private fun TodayDailyPlanDetails.needsPhoneScheduleFallback(): Boolean {
    val text = focus.trim()
    return text.isBlank() || MissingScheduleHints.any(text::contains)
}

internal fun weatherIndicatesRain(value: String): Boolean {
    val probability = RainProbabilityPattern.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (probability != null) return probability > 0
    if (value.contains("不用带伞") || value.contains("无需带伞") || value.contains("不用放")) {
        return false
    }
    return RainWords.any(value::contains)
}

internal fun normalizeDailyPlanLine(raw: String): String? {
    var line = raw.trim().trimStart('-', '•', '*').trim()
    if (line.isBlank()) return null
    if (
        line.count { it == '|' } >= 2 &&
        line.replace("|", "").all { it == '-' || it == ':' || it.isWhitespace() }
    ) {
        return null
    }
    if (line.startsWith("|") && line.endsWith("|")) {
        val cells = line.trim('|').split("|").map(String::trim)
        if (cells.any { it == "时间" } && cells.any { it == "安排" }) return null
        line = cells
            .filterNot { it.isBlank() || it == "—" || it == "-" }
            .joinToString(" · ")
    }
    return line.takeIf(String::isNotBlank)
}

internal fun dailyWeatherVisualWeight(updatedAt: Long, nowMillis: Long): Float {
    val ageHours = ((nowMillis - updatedAt) / HOUR_MILLIS).coerceAtLeast(0)
    return when {
        ageHours < 4 -> 1f
        ageHours < 8 -> 0.82f
        ageHours < 14 -> 0.62f
        else -> 0.42f
    }
}

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
    val scheduleParts = schedule.split(Regex("\\s+"), limit = 2)
    val hasDayPrefix = scheduleParts.size == 2 && ':' !in scheduleParts[0]
    val day = if (hasDayPrefix) scheduleParts[0] else ""
    val time = (if (hasDayPrefix) scheduleParts[1] else schedule)
        .takeIf { ':' in it }
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val metadata = parts.drop(1)
    val eventType = metadata.firstOrNull { it == "会议邀请" || it == "会议" || it == "日程" }
    val location = metadata
        .filterNot { it == eventType }
        .joinToString(" · ")
        .removePrefix("地点：")
        .removePrefix("地点:")
        .removePrefix("会议室：")
        .removePrefix("会议室:")
        .trim()
        .takeIf(String::isNotBlank)
    return CalendarCardDetails(
        day = day,
        time = time,
        eventType = eventType,
        location = location,
    )
}

private fun sectionBody(
    sections: List<TodayCardSummarySection>,
    vararg headings: String,
): String? = headings.firstNotNullOfOrNull { heading ->
    sections.firstOrNull { it.heading == heading }?.body?.takeIf(String::isNotBlank)
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
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS

private val RainProbabilityPattern = Regex("(?:降雨|降水)概率[^0-9]{0,8}(\\d{1,3})\\s*%")
private val RainWords = listOf("小雨", "中雨", "大雨", "暴雨", "阵雨", "有雨", "下雨", "雷雨")
private val SignalItemPattern = Regex("^(?:\\d+[.)、]|[-•])\\s*(.+)$")
private val SignalFieldPattern = Regex("(成熟度|证据强度|核心变化|不确定性|对我的意义)\\s*[：:]\\s*")
private val SignalNameSeparator = Regex("\\s*[｜|]\\s*")
private val SignalEvidenceLevelPattern = Regex("([1-3])")
private val WeatherSegmentSeparator = Regex("[，,；;|]")
private val WeatherHeadingPrefix = Regex("^(?:今日|明日|今天|明天)?(?:天气提醒|天气与穿着|天气)\\s*[：:]\\s*")
private val WeatherDayPrefix = Regex("^(?:今日|明日|今天|明天)\\s*")
private val WeatherLocationLabelPattern =
    Regex("(?:查询城市|城市|地点)\\s*[：:]\\s*([^。，,；;\\n（(]+)")
private val WeatherConditionLabelPattern = Regex(
    "(?:当前实况|实时实况|天气状况|天气)\\s*(?:[：:]|\\s)\\s*([^，,。；;\\n·]{1,16})",
)
private val WeatherTemperaturePattern =
    Regex("""-?\d+(?:\.\d+)?\s*(?:[~～\-至到]\s*-?\d+(?:\.\d+)?)?\s*(?:°\s*[CF]?|℃)""")
private val WeatherProbabilityDetailPattern = Regex("(?:降雨|降水)概率[^。；;\\n]*[。]?")
private val WeatherDetailSegmentPattern = Regex("(?:概率|带伞|伞|穿衣|保暖|湿度|紫外线|风力)")
private val WeatherConditionWords = listOf(
    "晴",
    "多云",
    "阴",
    "小雨",
    "中雨",
    "大雨",
    "暴雨",
    "阵雨",
    "雷雨",
    "雨",
    "雪",
    "雾",
    "霾",
    "风",
    "沙尘",
    "冰雹",
)
private val MissingScheduleHints = listOf(
    "暂无已同步的日程",
    "暂无日程",
    "没有日程",
    "未获取到",
    "未读取到",
    "无法获取",
    "未接通",
    "未连接",
)
private val ClockTimePattern = Regex("""\d{1,2}:\d{2}""")
private val ClockTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private data class SignalItemBuilder(
    val company: String,
    val subject: String?,
    var maturity: String? = null,
    var evidenceStrength: Int? = null,
    var change: String = "",
    var uncertainty: String? = null,
    var relevance: String? = null,
)

private val DailyPlanHeadings = setOf(
    "今日安排",
    "明日安排",
    "今日重点",
    "明日重点",
    "天气提醒",
    "天气与穿着",
    "行动建议",
    "今晚准备",
    "日程与出行",
)

private val TodayEvidenceJson = Json { ignoreUnknownKeys = true }
