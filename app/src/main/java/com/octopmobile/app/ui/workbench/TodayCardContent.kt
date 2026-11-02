package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.remote.dto.TodayCardActionState
import com.octopmobile.app.data.remote.dto.TodayEvidenceDto
import java.net.URI
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
    // Old clients persisted execution failures as cards. They are not user-facing
    // information and must disappear even before the server-side cleanup reaches Room.
    val visibleCards = cards.filterNot { card ->
        card.isInvalidExecutionFailure() || card.hasInvalidConversationEvidence()
    }
    val upcoming = visibleCards
        .filter { card ->
            card.kind == "calendar" &&
                card.localDate(zoneId).let { it.isAfter(today) && !it.isAfter(upcomingWindowEnd) }
        }
        .sortedWith(compareBy(TodayCardEntity::occurredAt, TodayCardEntity::title))
    val past = visibleCards
        .filter { card ->
            card.kind == "calendar" &&
                card.localDate(zoneId) == today &&
                card.occurredAt < nowMillis
        }
        .sortedWith(compareByDescending(TodayCardEntity::occurredAt))
    val outsideWindow = visibleCards.filter { card ->
        card.kind == "calendar" && card.localDate(zoneId).isAfter(upcomingWindowEnd)
    }
    val previousDays = visibleCards.filter { card ->
        card.kind == "calendar" && card.localDate(zoneId).isBefore(today)
    }
    val previousHealth = visibleCards
        .filter { card ->
            card.kind == "health" && card.localDate(zoneId).isBefore(today)
        }
    val todayHealth = visibleCards
        .filter { card ->
            card.kind == "health" && card.localDate(zoneId) == today
        }
    val latestTodayHealthByWindow = todayHealth
        .filter { it.healthWindow() in TimeSensitiveHealthWindows }
        .groupBy { requireNotNull(it.healthWindow()) }
        .mapValues { (_, cards) -> cards.latestHealthCard() }
    val staleTodayHealth = todayHealth.filter { card ->
        val window = card.healthWindow()
        window != null &&
            window in TimeSensitiveHealthWindows &&
            latestTodayHealthByWindow[window]?.id != card.id
    }
    val latestPreviousHealthByWindow = previousHealth
        .filter { it.healthWindow() in TimeSensitiveHealthWindows }
        .groupBy { requireNotNull(it.healthWindow()) }
        .mapValues { (_, cards) -> cards.latestHealthCard() }
    val history = previousHealth
        .filter { card ->
            val window = card.healthWindow()
            when {
                window == null || window !in TimeSensitiveHealthWindows -> true
                latestTodayHealthByWindow.containsKey(window) -> false
                else -> latestPreviousHealthByWindow[window]?.id == card.id
            }
        }
        .sortedWith(
            compareByDescending<TodayCardEntity>(TodayCardEntity::occurredAt)
                .thenByDescending(TodayCardEntity::updatedAt),
        )
    val excluded = upcoming.toSet() +
        past.toSet() +
        outsideWindow.toSet() +
        previousDays.toSet() +
        previousHealth.toSet() +
        staleTodayHealth.toSet()
    val current = visibleCards
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

private val TimeSensitiveHealthWindows = setOf("morning", "evening")

private fun List<TodayCardEntity>.latestHealthCard(): TodayCardEntity =
    maxWithOrNull(
        compareBy<TodayCardEntity>(TodayCardEntity::occurredAt)
            .thenBy(TodayCardEntity::updatedAt),
    )!!

private fun TodayCardEntity.healthWindow(): String? {
    parseHealthVisualization(summary)?.window?.let { return it }
    return when {
        title.startsWith("晚间健康") -> "evening"
        title.startsWith("昨晚睡眠") -> "morning"
        else -> null
    }
}

private val FailedExecutionSummaryPrefixes = listOf("本次执行失败：", "执行失败：")

private fun TodayCardEntity.isInvalidExecutionFailure(): Boolean {
    if (actionState != TodayCardActionState.FAILED) return false
    val text = summary.trimStart()
    return FailedExecutionSummaryPrefixes.any(text::startsWith)
}

private val FailedConversationExcerptPrefixes = listOf(
    "模型调用失败",
    "执行失败",
    "任务执行失败",
    "调用失败",
    "处理失败",
    "error:",
    "internal server error",
)

private fun TodayCardEntity.hasInvalidConversationEvidence(): Boolean {
    if (kind != "conversation") return false
    return decodeTodayCardEvidence(evidenceJson).any { evidence ->
        evidence.sourceType == "conversation" &&
            evidence.excerpt
                ?.trimStart()
                ?.lowercase()
                ?.let { text -> FailedConversationExcerptPrefixes.any(text::startsWith) } == true
    }
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
                ?.takeUnless { it.isInternalThreadId() || it.isBareUrl() }
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
                .filterNot { it.sourceType == "conversation" }
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

internal data class TodayCityWeather(
    val city: String,
    val condition: String?,
    val temperature: String?,
    val precipitation: String?,
    val note: String?,
)

internal data class TodayMultiCityWeather(
    val cities: List<TodayCityWeather>,
    val clothing: String?,
)

internal data class TodayReadingMetric(
    val label: String,
    val value: String,
)

internal data class TodayReadingBook(
    val date: String,
    val title: String,
    val marks: String,
)

internal data class TodayReadingSummary(
    val metrics: List<TodayReadingMetric>,
    val books: List<TodayReadingBook>,
    val remainingSections: List<TodayCardSummarySection>,
)

internal data class TodayRideDetails(
    val status: String,
    val plate: String?,
    val carModel: String?,
    val driver: String?,
    val distance: String?,
    val eta: String?,
    val route: String?,
    val orderId: String?,
)

internal data class TodayCoffeeDetails(
    val pickupCode: String,
    val qrPayload: String?,
    val status: String?,
    val shopName: String?,
    val productName: String?,
    val amount: String?,
    val readyAt: String?,
    val orderId: String?,
)

internal enum class TodayQuickReplyKind {
    RECONCILIATION,
    GENERIC,
}

internal data class TodayQuickReplySpec(
    val kind: TodayQuickReplyKind,
    val title: String,
    val request: String,
)

internal data class TodayDailyPlanDetails(
    val title: String,
    val focusHeading: String,
    val focus: String,
    val weatherHeading: String?,
    val weather: String?,
    val weatherDetails: TodayWeatherDetails?,
    val multiCityWeather: TodayMultiCityWeather?,
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

internal fun parseTodayReadingSummary(card: TodayCardEntity): TodayReadingSummary? {
    if (card.kind != "review") return null
    val sections = parseTodayCardSummary(card.summary)
    val overview = sections.firstOrNull {
        it.heading == "阅读概览" || it.heading == "本周阅读"
    } ?: return null
    val stats = ReadingStatPattern.findAll(overview.body)
        .map { match ->
            TodayReadingMetric(
                label = match.groupValues[1].trim(),
                value = match.groupValues[2].trim(),
            )
        }
        .toList()
    if (stats.isEmpty()) return null
    val books = sections.firstOrNull {
        it.heading == "本周在读" || it.heading == "阅读书目"
    }?.body
        ?.lineSequence()
        ?.mapNotNull(::normalizeDailyPlanLine)
        ?.mapNotNull { line ->
            val match = ReadingBookLinePattern.matchEntire(line) ?: return@mapNotNull null
            TodayReadingBook(
                date = match.groupValues[1].trim(),
                title = match.groupValues[2].trim().trim('《', '》'),
                marks = match.groupValues[3].trim(),
            )
        }
        ?.toList()
        .orEmpty()
    val consumedHeadings = setOf("阅读概览", "本周阅读", "本周在读", "阅读书目")
    return TodayReadingSummary(
        metrics = stats,
        books = books,
        remainingSections = sections.filterNot { it.heading in consumedHeadings },
    )
}

internal fun parseTodayRide(card: TodayCardEntity): TodayRideDetails? {
    val sections = parseTodayCardSummary(card.summary)
    val vehicle = sections.firstOrNull {
        it.heading == "车辆信息" || it.heading == "司机与车辆"
    }?.body.orEmpty()
    val trip = sections.firstOrNull {
        it.heading == "行程" || it.heading == "行程信息"
    }?.body.orEmpty()
    if (vehicle.isBlank() && trip.isBlank()) return null

    val status = summaryField(trip, "状态", "订单状态")
        ?: summaryField(vehicle, "状态", "订单状态")
        ?: trip.lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line.contains("匹配") ||
                    line.contains("司机已") ||
                    line.contains("行程中") ||
                    line.contains("行程已") ||
                    line.contains("订单已")
            }
    val plate = summaryField(vehicle, "车牌号", "车牌")
        ?: summaryField(trip, "车牌号", "车牌")
    val carModel = summaryField(vehicle, "车型", "车辆")
        ?: summaryField(trip, "车型", "车辆")
    val driver = summaryField(vehicle, "司机", "司机姓名")
    val distanceLine = summaryField(trip, "距接驾点", "距终点", "距离")
    val distanceParts = distanceLine
        ?.split('·')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    val distance = distanceParts.firstOrNull()?.takeIf { !it.startsWith("预计") }
    val eta = summaryField(trip, "预计", "预计到达", "预计时间", "ETA")
        ?: distanceParts.firstOrNull { it.startsWith("预计") }?.removePrefix("预计")
            ?.removeSuffix("到达")
            ?.trim()
    val route = trip.lineSequence()
        .map(String::trim)
        .firstOrNull { it.contains("→") }
        ?.takeIf(String::isNotBlank)
        ?: (summaryField(trip, "路线", "起终点")
            ?.takeIf { it.contains("→") })
    val orderId = summaryField(trip, "订单号", "订单 ID", "订单ID")
    val waiting = vehicle.lineSequence()
        .map(String::trim)
        .firstOrNull { it.contains("匹配") }

    if (status.isNullOrBlank() && plate.isNullOrBlank() && carModel.isNullOrBlank() &&
        driver.isNullOrBlank() && waiting.isNullOrBlank()
    ) {
        return null
    }
    return TodayRideDetails(
        status = status?.takeIf(String::isNotBlank)
            ?: waiting
            ?: "行程状态已更新",
        plate = plate,
        carModel = carModel,
        driver = driver,
        distance = distance,
        eta = eta,
        route = route,
        orderId = orderId,
    )
}

internal fun parseTodayCoffee(card: TodayCardEntity): TodayCoffeeDetails? {
    if (card.kind != "task") return null
    val sections = parseTodayCardSummary(card.summary)
    val pickup = sections.firstOrNull { it.heading == "取餐信息" } ?: return null
    val order = sections.firstOrNull { it.heading == "订单" }?.body.orEmpty()
    val pickupCode = summaryField(pickup.body, "取餐码")
        ?.takeIf { code ->
            code.length in 3..20 &&
                code.any(Char::isDigit) &&
                CoffeePickupCodePattern.matches(code)
        }
        ?: return null
    return TodayCoffeeDetails(
        pickupCode = pickupCode,
        qrPayload = summaryField(pickup.body, "取餐二维码", "二维码内容")
            ?.takeIf { payload ->
                payload.length in 8..256 && CoffeePickupQrPattern.matches(payload)
            },
        status = summaryField(order, "状态", "订单状态"),
        shopName = summaryField(pickup.body, "门店"),
        productName = summaryField(order, "饮品", "商品"),
        amount = summaryField(order, "实付", "金额"),
        readyAt = summaryField(pickup.body, "预计可取", "预计完成"),
        orderId = summaryField(order, "订单号", "订单 ID", "订单ID"),
    )
}

private fun summaryField(body: String, vararg labels: String): String? {
    if (body.isBlank()) return null
    return body.lineSequence()
        .map(String::trim)
        .firstNotNullOfOrNull { line ->
            val label = labels.firstOrNull { candidate ->
                line.startsWith("$candidate：") || line.startsWith("$candidate:")
            } ?: return@firstNotNullOfOrNull null
            line.drop(label.length + 1).trim().takeIf(String::isNotBlank)
        }
}

internal fun todayQuickReplySpec(card: TodayCardEntity): TodayQuickReplySpec? {
    if (card.kind != "task" || card.executionThreadId.isNullOrBlank()) return null
    if (parseTodayCoffee(card) != null) return null
    val kind = if (
        card.title.contains("对账") ||
        card.title.contains("账单") ||
        (card.summary.contains("余额") && card.summary.contains("刷卡"))
    ) {
        TodayQuickReplyKind.RECONCILIATION
    } else {
        TodayQuickReplyKind.GENERIC
    }
    val markerRequest = card.summary.lineSequence()
        .firstOrNull { line ->
            QuickReplyRequestMarkers.any(line::contains)
        }
        ?.trim()
    val request = markerRequest ?: if (kind == TodayQuickReplyKind.RECONCILIATION) {
        "补充本次账单金额、刷卡次数；如有实际扣款备注，也一并填写。"
    } else {
        "把这项任务还需要补充的信息发给执行 Agent，结果会回到原会话。"
    }
    return TodayQuickReplySpec(
        kind = kind,
        title = card.title,
        request = request,
    )
}

internal fun buildReconciliationReply(
    taskTitle: String,
    amount: String,
    swipes: String,
    note: String,
): String = buildString {
    appendLine("【${taskTitle.trim()} · 用户补充】")
    appendLine("账单金额：${amount.trim()} 元")
    appendLine("刷卡次数：${swipes.trim()} 次")
    note.trim().takeIf(String::isNotBlank)?.let { appendLine("补充说明：$it") }
    append("请按以上数据更新台账中的余额、交易记录和待确认事项，并给出更新后的余额与剩余可乘次数。")
}

internal fun buildGenericTaskReply(taskTitle: String, message: String): String =
    buildString {
        appendLine("【${taskTitle.trim()} · 用户补充】")
        appendLine(message.trim())
        append("请结合当前定时任务上下文继续处理，并更新结果。")
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
    val focus = cleanTodayResultText(
        sectionBody(
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
            ?: "暂无已同步的日程安排。",
    ).ifBlank { "暂无已同步的日程安排。" }
    val weather = sectionBody(
        sections,
        "天气提醒",
        "天气与穿着",
        "天气",
        "今日天气",
        "明日天气",
    )?.let(::cleanTodayResultText)?.takeIf(String::isNotBlank)
    val advice = sectionBody(
        sections,
        "行动建议",
        "今晚准备",
        "处理建议",
        "晨间建议",
        "夜间建议",
        "出行建议",
    )?.let(::cleanTodayResultText)?.takeIf(String::isNotBlank)
    return TodayDailyPlanDetails(
        title = if (isTomorrow) "明日安排与准备" else "今日安排与建议",
        focusHeading = if (isTomorrow) "明日安排" else "今日安排",
        focus = focus,
        weatherHeading = weather?.let { if (isTomorrow) "天气与穿着" else "天气提醒" },
        weather = weather,
        weatherDetails = weather?.let(::parseDailyWeather),
        multiCityWeather = weather?.let(::parseMultiCityWeather),
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
    val weatherLines = value.lineSequence()
        .mapNotNull(::normalizeDailyPlanLine)
        .filter(String::isNotBlank)
        .toList()
    val cityWeatherLines = weatherLines.count { line ->
        WeatherTemperaturePattern.containsMatchIn(line) &&
            (line.weatherCityMatchOrNull() != null ||
                line.weatherCityDayMatchOrNull() != null)
    }
    if (cityWeatherLines >= 2) return null

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

    val cityIndex = weatherLines.indexOfFirst { line ->
        line.weatherCityMatchOrNull() != null &&
            WeatherTemperaturePattern.containsMatchIn(line)
    }
    if (cityIndex >= 0) {
        val cityLine = weatherLines[cityIndex]
        val cityMatch = cityLine.weatherCityMatchOrNull() ?: return null
        val city = cityMatch.groupValues[1].trim()
        val body = cityMatch.groupValues[2].trim()
        val temperature = WeatherMinMaxPattern.find(body)?.let { match ->
            "${match.groupValues[1]}–${match.groupValues[2]}°C"
        } ?: WeatherTemperaturePattern.find(body)?.value
        val condition = WeatherConditionWords
            .sortedByDescending(String::length)
            .firstOrNull(body::contains)
        if (temperature != null && condition != null) {
            val detailLines = buildList {
                WeatherProbabilityValuePattern.find(body)?.groupValues?.getOrNull(1)?.let {
                    add("降雨概率 $it%")
                }
                addAll(weatherLines.drop(cityIndex + 1))
            }
            return TodayWeatherDetails(
                location = city,
                condition = condition,
                temperature = temperature,
                detail = detailLines
                    .joinToString("\n")
                    .takeIf(String::isNotBlank),
            )
        }
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

internal fun parseMultiCityWeather(value: String): TodayMultiCityWeather? {
    val accumulators = mutableListOf<CityWeatherAccumulator>()
    var current: CityWeatherAccumulator? = null
    var clothing: String? = null

    value.lineSequence()
        .mapNotNull(::normalizeDailyPlanLine)
        .filter(String::isNotBlank)
        .forEach { line ->
            val clothingMatch = WeatherClothingLinePattern.find(line)
            if (clothingMatch != null) {
                clothing = clothingMatch.groupValues[1].trim()
                current = null
                return@forEach
            }
            val cityMatch = line.weatherCityMatchOrNull()
            if (cityMatch != null && WeatherTemperaturePattern.containsMatchIn(line)) {
                current = CityWeatherAccumulator(
                    city = cityMatch.groupValues[1].trim(),
                    initialLine = line,
                    cityBody = cityMatch.groupValues[2].trim(),
                ).also(accumulators::add)
            } else {
                current?.continuations?.add(line)
            }
        }

    val cities = accumulators.mapNotNull(CityWeatherAccumulator::toCityWeather)
    if (cities.size < 2) return null
    return TodayMultiCityWeather(
        cities = cities,
        clothing = clothing?.let(::compactWeatherClothing),
    )
}

private fun String.weatherCityMatchOrNull(): MatchResult? {
    val match = WeatherCityLinePattern.find(this) ?: return null
    val city = match.groupValues[1]
    return match.takeUnless { WeatherNonCityPrefixes.any(city::contains) }
}

private fun String.weatherCityDayMatchOrNull(): MatchResult? {
    val match = WeatherCityDayPattern.find(this) ?: return null
    val city = match.groupValues[1]
    return match.takeUnless { WeatherNonCityPrefixes.any(city::contains) }
}

private class CityWeatherAccumulator(
    val city: String,
    val initialLine: String,
    val cityBody: String,
) {
    val continuations = mutableListOf<String>()

    fun toCityWeather(): TodayCityWeather? {
        val body = WeatherCityDatePrefixPattern.replace(cityBody, "").trim()
        val temperature = WeatherMinMaxPattern.find(body)?.let { match ->
            "${match.groupValues[1]}–${match.groupValues[2]}°C"
        } ?: WeatherTemperaturePattern.find(body)?.value
        if (temperature == null) return null
        val condition = WeatherConditionLabelPattern.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.substringBefore("（")
            ?.substringBefore("(")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: WeatherConditionWords
                .sortedByDescending(String::length)
                .firstOrNull(body::contains)
        val precipitation = WeatherProbabilityValuePattern.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "降雨 $it%" }
        val context = (listOf(initialLine) + continuations).joinToString("；")
        return TodayCityWeather(
            city = city,
            condition = condition,
            temperature = temperature,
            precipitation = precipitation,
            note = compactCityWeatherNote(context),
        )
    }
}

private fun compactCityWeatherNote(value: String): String? {
    val nightTemperature = WeatherNightTemperaturePattern.find(value)
        ?.groupValues
        ?.getOrNull(1)
    val hasCoat = value.contains("穿外套")
    if (value.contains("到京")) {
        return when {
            nightTemperature != null && hasCoat -> "到京后约 ${nightTemperature}°C，备外套"
            nightTemperature != null -> "到京后约 ${nightTemperature}°C"
            else -> "你明晚到京"
        }
    }

    val notes = mutableListOf<String>()
    if (value.contains("正在下大雨") || value.contains("降水强度")) {
        notes += "今晚有雨转停"
    }
    if (value.contains("回升") || WeatherTemperatureRisePattern.containsMatchIn(value)) {
        notes += "较今天回升"
    }
    if (nightTemperature != null && hasCoat) {
        notes += "夜里约 ${nightTemperature}°C，备外套"
    }
    return notes.distinct().joinToString(" · ").takeIf(String::isNotBlank)
}

private fun compactWeatherClothing(value: String): String? {
    val text = cleanTodayResultText(value)
    if (text.isBlank()) return null
    val hints = mutableListOf<String>()
    if (text.contains("薄外套")) {
        hints += buildString {
            if (text.contains("早晚偏凉")) append("早晚偏凉，")
            append("薄外套即可")
        }
    }
    if (text.contains("外套随身")) hints += "返京随身带"
    return hints.distinct().joinToString("；").takeIf(String::isNotBlank)
        ?: text.substringBefore("。").take(36).takeIf(String::isNotBlank)
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

internal fun cleanTodayResultText(value: String): String =
    value.lineSequence()
        .mapNotNull { raw ->
            val line = raw
                .replace(TodaySourceSuffixPattern, "")
                .trim()
            when {
                line.isBlank() -> null
                InternalTodayProcessMarkers.any { line.contains(it, ignoreCase = true) } -> null
                else -> line
            }
        }
        .joinToString("\n")

internal fun normalizeDailyPlanLine(raw: String): String? {
    var line = cleanTodayResultText(raw)
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.trimStart('-', '•', '*')
        ?.trim()
        ?: return null
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
            val url = item.url?.trim()?.takeIf(String::isNotBlank)
            val title = normalizeTodayFactTitle(item.title, url)
                .takeIf { it.isMeaningfulTodayFactText(url) }
                ?: normalizeTodayFactTitle(item.excerpt, url)
                    .take(80)
                    .takeIf { it.isMeaningfulTodayFactText(url) }
                ?: url?.todayFactFallbackTitle()
                ?: return@mapNotNull null
            val key = url ?: title.lowercase()
            if (!seen.add(key)) return@mapNotNull null
            TodayCardFact(
                title = title,
                excerpt = item.excerpt?.trim()?.takeIf {
                    it.isNotBlank() && it != title && it.isMeaningfulTodayFactText(url)
                },
                sourceType = item.sourceType,
                sourceRecordId = item.sourceRecordId,
                url = url,
            )
        }
        .take(limit)
        .toList()
}

internal fun normalizeTodayFactTitle(value: String?, url: String?): String {
    var title = value.orEmpty().trim()
    if (TodayStructuredUiTitlePattern.containsMatchIn(title)) return ""
    title = TodayFactTablePrefixPattern.replace(title, "")
    title = TodayFactInlineNumberPattern.replace(title, "")
    title = TodayFactSourceLabelPattern.replace(title, "")
    title = TodayFactTrailingTablePattern.replace(title, "")
    title = title.trim(' ', '-', '—', ':', '：', '|', '｜')
    if (TodaySourceTimeTitlePattern.matches(title)) return ""
    if (title.lowercase() in TodayWeakFactTitles) return ""
    return title
}

private fun String.isMeaningfulTodayFactText(url: String?): Boolean {
    val host = url?.todayFactHost()
    val withoutSourceLabel = replace(
        Regex("^(?:来源|source)\\s*[:：]?", RegexOption.IGNORE_CASE),
        "",
    )
    val withoutHost = if (host == null) {
        withoutSourceLabel
    } else {
        withoutSourceLabel.replace(host, "", ignoreCase = true)
    }
    return withoutHost.replace(TodayFactTitleNoisePattern, "").isNotEmpty()
}

private fun String.todayFactFallbackTitle(): String? =
    runCatching { URI(this) }
        .getOrNull()
        ?.let { uri ->
            val host = uri.host?.removePrefix("www.")?.takeIf(String::isNotBlank)
            val repository = if (host == "github.com") {
                uri.path
                    ?.trim('/')
                    ?.split('/')
                    ?.take(2)
                    ?.takeIf { it.size == 2 }
                    ?.joinToString("/")
            } else {
                null
            }
            repository ?: host
        }

private fun String.todayFactHost(): String? =
    runCatching { URI(this).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?.takeIf(String::isNotBlank)

private val TodayFactTitleNoisePattern =
    Regex("[\\s·|｜:：;；,，.。!?！？()（）\\[\\]【】<>《》\\-—–]+")
private val TodayFactTablePrefixPattern =
    Regex("""^\s*\|\s*S?\d+\s*\|\s*""", RegexOption.IGNORE_CASE)
private val TodayFactInlineNumberPattern =
    Regex("""^\s*S?\d+\s*[|｜]\s*""", RegexOption.IGNORE_CASE)
private val TodayFactSourceLabelPattern = Regex(
    """^\s*[「【\[]?\s*(?:站点直读|连接器原文)\s*[」】\]]?\s*""",
    RegexOption.IGNORE_CASE,
)
private val TodayFactTrailingTablePattern = Regex("""(?:\s*[|｜]\s*)+$""")
private val TodaySourceTimeTitlePattern =
    Regex("""^(?:[^：:|｜]{1,24}\s+)?\d{1,2}[-/]\d{1,2}\s+\d{1,2}:\d{2}$""")
private val TodayWeakFactTitles = setOf(
    "站点直读",
    "连接器原文",
    "来源",
    "source",
)
private val TodayStructuredUiTitlePattern = Regex(
    """^\s*\{.*?"octop_ui"\s*:""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

internal fun todayNewsFacts(evidence: List<TodayEvidenceDto>): List<TodayCardFact> =
    todayCardFacts(evidence, limit = Int.MAX_VALUE)
        .filter { it.url != null || it.sourceType == "web" || it.sourceType == "web_search" }

private fun TodayCardEntity.localDate(zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(occurredAt).atZone(zoneId).toLocalDate()

private fun String.compactSnippet(maxLength: Int = 100): String =
    replace(Regex("\\s+"), " ").trim().take(maxLength)

private fun String.isInternalThreadId(): Boolean =
    trim().matches(InternalThreadIdPattern)

private fun String.isBareUrl(): Boolean =
    trim().matches(BareUrlPattern)

private val InternalThreadIdPattern =
    Regex("(?i)^(?:draft-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val BareUrlPattern =
    Regex("(?i)^https?://\\S+(?:\\.\\.\\.|…)?$")

private val SummaryHeadingPattern = Regex("(?m)^【([^】]+)】\\s*$")

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS

private val RainProbabilityPattern = Regex("(?:降雨|降水)概率[^0-9]{0,8}(\\d{1,3})\\s*%")
private val RainWords = listOf("小雨", "中雨", "大雨", "暴雨", "阵雨", "有雨", "下雨", "雷雨")
private val SignalItemPattern = Regex("^(?:\\d+[.)、]|[-•])\\s*(.+)$")
private val SignalFieldPattern = Regex("(成熟度|证据强度|核心变化|不确定性|对我的意义)\\s*[：:]\\s*")
private val SignalNameSeparator = Regex("\\s*[｜|]\\s*")
private val SignalEvidenceLevelPattern = Regex("([1-3])")
private val CoffeePickupCodePattern = Regex("""^[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*$""")
private val CoffeePickupQrPattern = Regex("""^[A-Za-z0-9+/=_-]+$""")
private val ReadingStatPattern = Regex("""(本周阅读|新增划线|书架)\s*(\d+)\s*(?:本|条)""")
private val ReadingBookLinePattern = Regex("""^(\d{1,2}[-/]\d{1,2})\s*《([^》]+)》\s*[·｜|]\s*(.+)$""")
private val QuickReplyRequestMarkers = listOf(
    "想跟你确认",
    "请确认",
    "请补充",
    "需要你提供",
    "告诉我",
    "给我个准数",
    "需要你录入",
)
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
private val WeatherCityDayPattern =
    Regex("^([\\u4e00-\\u9fff]{2,10})(?:今天|今日|明天|明日)")
private val WeatherCityLinePattern =
    Regex("^([\\u4e00-\\u9fff]{2,10})(?:[（(][^）)]*[）)])?\\s*[：:]\\s*(.*)$")
private val WeatherNonCityPrefixes = listOf(
    "对比",
    "今天",
    "今晚",
    "实时",
    "穿衣",
    "带伞",
    "温度",
    "降雨",
    "降水",
    "最高",
    "最低",
)
private val WeatherClothingLinePattern = Regex("^穿着\\s*[：:]\\s*(.+)$")
private val WeatherCityDatePrefixPattern =
    Regex("^(?:今天|今日|明天|明日)?\\s*\\d{1,2}[-/]\\d{1,2}\\s*[：:]?\\s*")
private val WeatherMinMaxPattern = Regex(
    """最低\s*(-?\d+(?:\.\d+)?)\s*(?:°\s*C|℃)\s*[/／]\s*最高\s*(-?\d+(?:\.\d+)?)\s*(?:°\s*C|℃)""",
    RegexOption.IGNORE_CASE,
)
private val WeatherProbabilityValuePattern =
    Regex("""(?:降雨|降水)概率\s*(\d{1,3}(?:\.\d+)?)\s*%""")
private val WeatherNightTemperaturePattern =
    Regex("""夜里\s*\d{1,2}\s*点后约\s*(-?\d+(?:\.\d+)?)\s*(?:°C|℃)""")
private val WeatherTemperatureRisePattern =
    Regex("""最高\s*升\s*\d+(?:\.\d+)?\s*(?:°C|℃)""")
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
private val TodaySourceSuffixPattern = Regex(
    """\s*(?:——|—|；|;)?\s*(?:来源|数据来源)\s*[：:][^。\n]*""",
    RegexOption.IGNORE_CASE,
)
private val InternalTodayProcessMarkers = listOf(
    "位置判断",
    "查询城市",
    "飞书日程",
    "连接器",
    "机器人身份",
    "权限没",
    "权限未",
    "未接通",
    "MCP",
    "Open-Meteo",
    "内置源",
    "内置天气",
    "只吃经纬度",
    "我用的是",
    "按你的规则",
    "工具调用",
    "调用过程",
    "接口被限流",
    "No events in this time range",
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
