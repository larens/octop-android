package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.octopmobile.app.R
import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.remote.dto.TodayCardActionState
import com.octopmobile.app.data.remote.dto.TodayFeedbackType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun TodayRecommendationCard(
    card: TodayCardEntity,
    isPast: Boolean,
    nowMillis: Long,
    phoneScheduleCards: List<TodayCardEntity> = emptyList(),
    onOpenDetail: () -> Unit,
    onProcess: () -> Unit,
    onOpenExecution: (() -> Unit)?,
    onSnooze: () -> Unit,
    onFeedback: (String) -> Unit,
    onClose: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(22.dp)
    val calendar = remember(card.summary) {
        if (card.kind == "calendar") parseCalendarCardSummary(card.summary) else null
    }
    val summarySections = remember(card.summary) { parseTodayCardSummary(card.summary) }
    val healthVisualization = remember(card.kind, card.summary) {
        if (card.kind == "health") parseHealthVisualization(card.summary) else null
    }
    val dailyPlan = remember(card.kind, card.title, card.summary, phoneScheduleCards, nowMillis) {
        resolveTodayDailyPlan(card, phoneScheduleCards, nowMillis)
    }
    val industrySignal = remember(card.kind, card.summary) {
        parseTodaySignal(card)
    }
    val dailyWeatherWeight = remember(card.updatedAt, nowMillis) {
        dailyWeatherVisualWeight(card.updatedAt, nowMillis)
    }
    val summaryPreview = remember(card.summary) { todayCardSummaryPreview(card.summary) }
    val structuredAdvice = summarySections.firstOrNull {
        it.heading == "处理建议" ||
            it.heading == "改善建议" ||
            it.heading == "行动建议" ||
            it.heading == "今晚准备"
    }
    val hasStructuredAdvice =
        structuredAdvice != null || dailyPlan != null || industrySignal != null
    val structuredHealthAdvice = structuredAdvice?.takeIf { card.kind == "health" }
    val structuredEmail = card.kind == "inbox" && hasStructuredAdvice
    val hasPendingMail = structuredEmail && summarySections.any { it.heading == "待处理邮件" }
    val pendingPreview = summarySections
        .firstOrNull { it.heading == "待处理邮件" }
        ?.body
        .orEmpty()
    val accent = if (hasPendingMail) PendingMailAccent else todayCardAccent(card.kind, isPast)
    val executionCompleted = card.actionState == TodayCardActionState.COMPLETED
    val evidence = remember(card.evidenceJson) { decodeTodayCardEvidence(card.evidenceJson) }
    val newsFacts = remember(card.evidenceJson) { todayNewsFacts(evidence) }
    val facts = remember(card.kind, card.evidenceJson) {
        when {
            card.kind == "health" || dailyPlan != null || industrySignal != null -> emptyList()
            card.kind.isNewsCard() -> newsFacts.take(3)
            else -> todayCardFacts(evidence)
        }
    }
    val isUpcoming = remember(card.occurredAt) {
        Instant.ofEpochMilli(card.occurredAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .isAfter(LocalDate.now())
    }
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    val opensFullNewsList = card.kind.isNewsCard() && newsFacts.size > facts.size

    if (calendar != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            accent.copy(alpha = if (isPast) 0.035f else 0.075f),
                        ),
                    ),
                )
                .border(1.dp, accent.copy(alpha = 0.14f), shape)
                .clickable(onClick = onOpenDetail),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryPill(kind = "calendar", accent = accent)
                    Spacer(Modifier.width(8.dp))
                    if (card.lifecycle == "unread") {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (card.riskLevel == "A3" || card.riskLevel == "A4") {
                        Text(
                            text = stringResource(R.string.today_card_needs_confirmation),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreHoriz,
                                contentDescription = stringResource(R.string.today_card_more_actions),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        TodayCardMenu(
                            expanded = menuExpanded,
                            card = card,
                            onDismiss = { menuExpanded = false },
                            onSnooze = onSnooze,
                            onFeedback = onFeedback,
                            onClose = onClose,
                            sourceUrl = card.sourceUrl,
                            onOpenSource = { url -> uriHandler.openUri(url) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Event,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    calendar.time?.let { time ->
                        Text(
                            text = time,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = accent,
                        )
                    }
                    calendar.location?.let { location ->
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = stringResource(
                                R.string.today_card_calendar_location_value,
                                location,
                            ),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!executionCompleted) {
                        Spacer(Modifier.width(8.dp))
                        CompactCalendarAction(
                            card = card,
                            accent = accent,
                            isPast = isPast,
                            isUpcoming = isUpcoming,
                            onOpenDetail = onOpenDetail,
                            onProcess = onProcess,
                            onOpenExecution = onOpenExecution,
                        )
                    }
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        accent.copy(alpha = if (isPast) 0.035f else 0.075f),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.14f), shape)
            .clickable(onClick = onOpenDetail),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryPill(
                    kind = card.kind,
                    accent = accent,
                    label = when {
                        hasPendingMail -> stringResource(R.string.today_card_pending_mail)
                        dailyPlan != null -> stringResource(
                            if (dailyPlan.isTomorrow) {
                                R.string.today_card_category_tomorrow_plan
                            } else {
                                R.string.today_card_category_daily_plan
                            },
                        )
                        else -> null
                    },
                )
                Spacer(Modifier.width(8.dp))
                if (card.lifecycle == "unread") {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (card.riskLevel == "A3" || card.riskLevel == "A4") {
                    Text(
                        text = stringResource(R.string.today_card_needs_confirmation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            contentDescription = stringResource(R.string.today_card_more_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TodayCardMenu(
                        expanded = menuExpanded,
                        card = card,
                        onDismiss = { menuExpanded = false },
                        onSnooze = onSnooze,
                        onFeedback = onFeedback,
                        onClose = onClose,
                        sourceUrl = card.sourceUrl,
                        onOpenSource = { url -> uriHandler.openUri(url) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = dailyPlan?.title ?: card.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (
                dailyPlan == null &&
                industrySignal == null &&
                summaryPreview.isNotBlank()
            ) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = summaryPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (card.kind == "health") 12 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (dailyPlan != null) {
                Spacer(Modifier.height(12.dp))
                DailyPlanPanel(
                    details = dailyPlan,
                    accent = accent,
                    weatherVisualWeight = dailyWeatherWeight,
                )
            } else if (industrySignal != null) {
                Spacer(Modifier.height(12.dp))
                IndustrySignalPanel(
                    details = industrySignal,
                    accent = accent,
                )
            } else if (healthVisualization != null) {
                Spacer(Modifier.height(12.dp))
                HealthVisualPanel(healthVisualization, accent)
            }

            if (dailyPlan == null && industrySignal == null) {
                if (hasPendingMail) {
                    Spacer(Modifier.height(12.dp))
                    PendingMailPanel(pendingPreview, accent)
                } else if (facts.isNotEmpty() && !structuredEmail && card.kind != "health") {
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        facts.forEachIndexed { index, fact ->
                            FactCard(
                                index = index,
                                fact = fact,
                                accent = accent,
                                onOpen = fact.url?.let { url -> { uriHandler.openUri(url) } },
                            )
                        }
                    }
                }
            }

            if (!hasStructuredAdvice && card.kind != "calendar") {
                Spacer(Modifier.height(12.dp))
                AdvicePanel(
                    text = todayCardAdvice(card, isPast, isUpcoming),
                    accent = accent,
                )
            } else if (structuredHealthAdvice != null) {
                Spacer(Modifier.height(12.dp))
                AdvicePanel(
                    text = structuredHealthAdvice.body,
                    accent = accent,
                )
            }

            if (!hasStructuredAdvice && !executionCompleted) {
                card.actionState?.let { state ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(todayActionStateColor(state)),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = todayActionStateName(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = todayActionStateColor(state),
                        )
                    }
                    card.actionError?.takeIf(String::isNotBlank)?.let { error ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val primaryAction = when {
                dailyPlan != null -> onOpenDetail
                industrySignal != null -> onOpenDetail
                opensFullNewsList -> onOpenDetail
                structuredEmail -> onOpenDetail
                onOpenExecution != null -> onOpenExecution
                isPast -> onOpenDetail
                else -> onProcess
            }
            if (
                structuredEmail ||
                !executionCompleted ||
                dailyPlan != null ||
                industrySignal != null
            ) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = primaryAction,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPast) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                accent
                            },
                            contentColor = if (isPast) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                Color.White
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                dailyPlan != null -> Icons.Filled.ChevronRight
                                industrySignal != null -> Icons.Filled.ChevronRight
                                structuredEmail -> Icons.Filled.ChevronRight
                                isPast -> Icons.Filled.History
                                else -> Icons.Filled.AutoAwesome
                            },
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = when {
                                dailyPlan != null -> stringResource(R.string.today_card_action_view_plan)
                                industrySignal != null ->
                                    stringResource(R.string.today_signal_view_analysis)
                                opensFullNewsList -> {
                                    stringResource(R.string.today_card_view_all_news, newsFacts.size)
                                }
                                structuredEmail -> stringResource(
                                    if (hasPendingMail) {
                                        R.string.today_card_action_view_pending
                                    } else {
                                        R.string.today_card_action_view_details
                                    },
                                )
                                else -> todayCardActionLabel(
                                    card = card,
                                    isPast = isPast,
                                    isUpcoming = isUpcoming,
                                    hasExecution = onOpenExecution != null,
                                )
                            },
                        )
                    }
                    if (dailyPlan != null || industrySignal != null) {
                        OutlinedButton(
                            onClick = onClose,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.today_card_understood))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TodayDailyReviewCard(
    completedSchedules: List<TodayCalendarItemSummary>,
    conversations: List<TodayReviewItem>,
    updates: List<TodayReviewItem>,
    isYesterday: Boolean,
) {
    val accent = Color(0xFF188C78)
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        accent.copy(alpha = 0.075f),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.14f), shape),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CategoryPill("review", accent)
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    if (isYesterday) {
                        R.string.today_daily_review_title_yesterday
                    } else {
                        R.string.today_daily_review_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(
                    R.string.today_daily_review_summary,
                    completedSchedules.size,
                    conversations.size,
                    updates.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (completedSchedules.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                TodayReviewSectionTitle(
                    text = stringResource(R.string.today_daily_review_schedules),
                    accent = accent,
                )
                Spacer(Modifier.height(7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    completedSchedules.take(8).forEachIndexed { index, item ->
                        TodayReviewRow(
                            index = index,
                            title = item.title,
                            label = item.time,
                            detail = null,
                            accent = accent,
                        )
                    }
                }
            }

            if (conversations.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                TodayReviewSectionTitle(
                    text = stringResource(R.string.today_daily_review_conversations),
                    accent = accent,
                )
                Spacer(Modifier.height(7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    conversations.forEachIndexed { index, item ->
                        TodayReviewRow(
                            index = index,
                            title = item.title,
                            label = null,
                            detail = item.detail,
                            accent = accent,
                        )
                    }
                }
            }

            if (updates.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                TodayReviewSectionTitle(
                    text = stringResource(R.string.today_daily_review_updates),
                    accent = accent,
                )
                Spacer(Modifier.height(7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    updates.forEachIndexed { index, item ->
                        TodayReviewRow(
                            index = index,
                            title = item.title,
                            label = null,
                            detail = item.detail,
                            accent = accent,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            AdvicePanel(
                text = stringResource(
                    if (isYesterday) {
                        R.string.today_daily_review_advice_yesterday
                    } else {
                        R.string.today_daily_review_advice
                    },
                ),
                accent = accent,
            )
        }
    }
}

@Composable
internal fun TodayUpcomingScheduleCard(summary: TodayUpcomingCalendarSummary) {
    val accent = Color(0xFF0E9BC8)
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        accent.copy(alpha = 0.075f),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.14f), shape),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CategoryPill("calendar", accent)
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.today_upcoming_cards_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = when {
                    summary.tomorrow.isNotEmpty() -> stringResource(
                        R.string.today_upcoming_cards_tomorrow,
                        summary.tomorrow.size,
                        summary.laterCount,
                    )
                    summary.laterCount > 0 -> stringResource(
                        R.string.today_upcoming_cards_without_tomorrow,
                        summary.laterCount,
                    )
                    else -> stringResource(R.string.today_upcoming_cards_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (summary.tomorrow.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                TodayReviewSectionTitle(
                    text = stringResource(R.string.today_upcoming_tomorrow_title),
                    accent = accent,
                )
                Spacer(Modifier.height(7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    summary.tomorrow.forEachIndexed { index, item ->
                        TodayReviewRow(
                            index = index,
                            title = item.title,
                            label = item.time,
                            detail = null,
                            accent = accent,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            AdvicePanel(
                text = stringResource(R.string.today_upcoming_cards_advice),
                accent = accent,
            )
        }
    }
}

@Composable
private fun TodayReviewSectionTitle(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodayReviewRow(
    index: Int,
    title: String,
    label: String?,
    detail: String?,
    accent: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.width(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val supporting = listOfNotNull(label, detail).joinToString(" · ")
                if (supporting.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TodayRecommendationDetail(
    card: TodayCardEntity,
    isPast: Boolean,
    nowMillis: Long,
    phoneScheduleCards: List<TodayCardEntity> = emptyList(),
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onFeedback: (String) -> Unit,
    onOpenEvidenceThread: (String) -> Unit,
    onOpenExecution: (() -> Unit)?,
    onProcess: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val calendar = remember(card.summary) {
        if (card.kind == "calendar") parseCalendarCardSummary(card.summary) else null
    }
    val summarySections = remember(card.summary) {
        parseTodayCardSummary(card.summary).filterNot { it.heading == "健康可视化" }
    }
    val healthVisualization = remember(card.kind, card.summary) {
        if (card.kind == "health") parseHealthVisualization(card.summary) else null
    }
    val dailyPlan = remember(card.kind, card.title, card.summary, phoneScheduleCards, nowMillis) {
        resolveTodayDailyPlan(card, phoneScheduleCards, nowMillis)
    }
    val industrySignal = remember(card.kind, card.summary) {
        parseTodaySignal(card)
    }
    val hasStructuredAdvice = dailyPlan != null || industrySignal != null || summarySections.any {
        it.heading == "处理建议" ||
            it.heading == "改善建议" ||
            it.heading == "行动建议" ||
            it.heading == "今晚准备"
    }
    val structuredEmail = card.kind == "inbox" && hasStructuredAdvice
    val hasPendingMail = structuredEmail && summarySections.any { it.heading == "待处理邮件" }
    val accent = if (hasPendingMail) PendingMailAccent else todayCardAccent(card.kind, isPast)
    val executionCompleted = card.actionState == TodayCardActionState.COMPLETED
    val sectionsBeforeFacts = if (structuredEmail) {
        summarySections.filter { it.heading == "收件概览" }
    } else {
        summarySections
    }
    val sectionsAfterFacts = if (structuredEmail) {
        summarySections.filterNot { it.heading == "收件概览" }
    } else {
        emptyList()
    }
    val evidence = remember(card.evidenceJson) { decodeTodayCardEvidence(card.evidenceJson) }
    val facts = remember(card.kind, card.evidenceJson) {
        when {
            card.kind == "health" -> emptyList()
            card.kind.isNewsCard() -> todayNewsFacts(evidence)
            else -> todayCardFacts(evidence, limit = 6)
        }
    }
    val contactFacts = if (structuredEmail) {
        facts.filterNot { it.title == "邮件汇总结果" }
    } else {
        facts
    }
    val isUpcoming = remember(card.occurredAt) {
        Instant.ofEpochMilli(card.occurredAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .isAfter(LocalDate.now())
    }
    var feedbackExpanded by remember(card.id) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f),
                shape = RoundedCornerShape(
                    topStart = 26.dp,
                    topEnd = 26.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 18.dp,
                ),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 10.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryPill(
                            kind = card.kind,
                            accent = accent,
                            label = when {
                                hasPendingMail -> stringResource(R.string.today_card_pending_mail)
                                dailyPlan != null -> stringResource(
                                    if (dailyPlan.isTomorrow) {
                                        R.string.today_card_category_tomorrow_plan
                                    } else {
                                        R.string.today_card_category_daily_plan
                                    },
                                )
                                else -> null
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.today_card_detail_close),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                    ) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = dailyPlan?.title ?: card.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        if (dailyPlan != null) {
                            Spacer(Modifier.height(12.dp))
                            DailyPlanPanel(
                                details = dailyPlan,
                                accent = accent,
                                weatherVisualWeight = dailyWeatherVisualWeight(
                                    card.updatedAt,
                                    System.currentTimeMillis(),
                                ),
                                expanded = true,
                            )
                        }
                        if (industrySignal != null) {
                            Spacer(Modifier.height(12.dp))
                            IndustrySignalPanel(
                                details = industrySignal,
                                accent = accent,
                                expanded = true,
                            )
                        }
                        if (healthVisualization != null) {
                            Spacer(Modifier.height(12.dp))
                            HealthVisualPanel(healthVisualization, accent)
                        }
                        if (
                            dailyPlan == null &&
                            industrySignal == null &&
                            calendar == null &&
                            sectionsBeforeFacts.isNotEmpty()
                        ) {
                            SummarySectionList(sectionsBeforeFacts)
                        }
                        if (calendar != null) {
                            Spacer(Modifier.height(14.dp))
                            CalendarFocusPanel(calendar, accent, expanded = true)
                        }

                        if (
                            calendar == null &&
                            contactFacts.isNotEmpty() &&
                            (!structuredEmail || hasPendingMail)
                        ) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle(
                                if (industrySignal != null) {
                                    stringResource(
                                        R.string.today_signal_evidence_sources,
                                        contactFacts.size,
                                    )
                                } else if (card.kind.isNewsCard()) {
                                    stringResource(
                                        R.string.today_card_news_list_title,
                                        contactFacts.size,
                                    )
                                } else if (structuredEmail && hasPendingMail) {
                                    stringResource(R.string.today_card_pending_contacts)
                                } else if (structuredEmail) {
                                    stringResource(R.string.today_card_important_contacts)
                                } else {
                                    stringResource(R.string.today_card_related_content)
                                },
                            )
                            Spacer(Modifier.height(9.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                contactFacts.forEachIndexed { index, fact ->
                                    val onOpen = when {
                                        fact.url != null -> {
                                            { uriHandler.openUri(requireNotNull(fact.url)) }
                                        }
                                        fact.sourceType == "conversation" &&
                                            !fact.sourceRecordId.isNullOrBlank() -> {
                                            { onOpenEvidenceThread(requireNotNull(fact.sourceRecordId)) }
                                        }
                                        else -> null
                                    }
                                    if (structuredEmail) {
                                        ContactHighlightCard(
                                            fact = fact,
                                            accent = accent,
                                            onOpen = onOpen,
                                        )
                                    } else {
                                        FactCard(
                                            index = index,
                                            fact = fact,
                                            accent = accent,
                                            onOpen = onOpen,
                                        )
                                    }
                                }
                            }
                        }

                        if (
                            dailyPlan == null &&
                            industrySignal == null &&
                            calendar == null &&
                            sectionsAfterFacts.isNotEmpty()
                        ) {
                            SummarySectionList(sectionsAfterFacts)
                        }

                        if (!hasStructuredAdvice) {
                            Spacer(Modifier.height(16.dp))
                            AdvicePanel(
                                text = todayCardAdvice(card, isPast, isUpcoming),
                                accent = accent,
                            )
                        }

                        if (!hasStructuredAdvice && !executionCompleted) {
                            card.actionState?.let { state ->
                                Spacer(Modifier.height(20.dp))
                                SectionTitle(stringResource(R.string.today_card_detail_execution))
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    text = todayActionStateName(state),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = todayActionStateColor(state),
                                )
                                card.actionError?.takeIf(String::isNotBlank)?.let { error ->
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }

                        if (card.riskLevel == "A3" || card.riskLevel == "A4") {
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = stringResource(R.string.today_card_detail_confirmation_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (card.supportsScheduleReminder()) {
                            IconButton(onClick = onSnooze) {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = stringResource(R.string.today_card_snooze),
                                )
                            }
                        }
                        Box {
                            TextButton(onClick = { feedbackExpanded = true }) {
                                Text(stringResource(R.string.today_card_feedback))
                            }
                            TodayFeedbackMenu(
                                expanded = feedbackExpanded,
                                onDismiss = { feedbackExpanded = false },
                                onFeedback = {
                                    feedbackExpanded = false
                                    onFeedback(it)
                                },
                                showPoorSource = card.isNewsRecommendationCard(),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (!isPast && !structuredEmail && !executionCompleted) {
                            Button(
                                onClick = onOpenExecution ?: onProcess,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accent,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    todayCardActionLabel(
                                        card = card,
                                        isPast = false,
                                        isUpcoming = isUpcoming,
                                        hasExecution = onOpenExecution != null,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCalendarAction(
    card: TodayCardEntity,
    accent: Color,
    isPast: Boolean,
    isUpcoming: Boolean,
    onOpenDetail: () -> Unit,
    onProcess: () -> Unit,
    onOpenExecution: (() -> Unit)?,
) {
    val action: () -> Unit = when {
        onOpenExecution != null -> onOpenExecution
        isPast -> onOpenDetail
        card.riskLevel == "A3" || card.riskLevel == "A4" -> onOpenDetail
        else -> onProcess
    }
    val label = when {
        onOpenExecution != null -> todayCardActionLabel(
            card = card,
            isPast = isPast,
            isUpcoming = isUpcoming,
            hasExecution = true,
        )
        isPast -> stringResource(R.string.today_card_action_view_details)
        card.riskLevel == "A3" || card.riskLevel == "A4" ->
            stringResource(R.string.today_card_action_confirm)
        else -> stringResource(R.string.today_card_action_prepare_short)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent)
            .clickable(onClick = action)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (onOpenExecution != null) {
                Icons.Filled.ChevronRight
            } else {
                Icons.Filled.AutoAwesome
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun CategoryPill(kind: String, accent: Color, label: String? = null) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = todayCardIcon(kind),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label ?: todayCardCategoryName(kind),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

@Composable
private fun CalendarFocusPanel(
    details: CalendarCardDetails,
    accent: Color,
    expanded: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(13.dp),
            ),
        shape = RoundedCornerShape(13.dp),
        color = accent.copy(alpha = 0.055f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = details.day,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                details.time?.let { time ->
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = accent,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                details.eventType?.let { eventType ->
                    Text(
                        text = eventType,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                details.location?.let { location ->
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = location,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun IndustrySignalPanel(
    details: TodaySignalDetails,
    accent: Color,
    expanded: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(17.dp),
            color = accent.copy(alpha = 0.08f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.16f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.15f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.today_signal_judgment),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = details.judgment,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(17.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                details.signals
                    .take(if (expanded) 3 else 2)
                    .forEachIndexed { index, signal ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 11.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                            )
                        }
                        IndustrySignalEvent(
                            signal = signal,
                            accent = accent,
                            expanded = expanded,
                        )
                    }
            }
        }

        details.advice?.let { advice ->
            AdvicePanel(text = advice, accent = accent)
        }
    }
}

@Composable
private fun IndustrySignalEvent(
    signal: TodayIndustrySignal,
    accent: Color,
    expanded: Boolean,
) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = signal.company,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                signal.subject?.let { subject ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subject,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            signal.maturity?.let { maturity ->
                Spacer(Modifier.width(8.dp))
                SignalMaturityPill(maturity)
            }
        }

        signal.evidenceStrength?.let { strength ->
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.today_signal_evidence_strength),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                SignalEvidenceMeter(strength = strength, accent = accent)
            }
        }

        Spacer(Modifier.height(9.dp))
        Text(
            text = signal.change,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )

        signal.uncertainty?.let { uncertainty ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SignalVendorClaimColor.copy(alpha = 0.08f))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.today_signal_uncertainty),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = SignalVendorClaimColor,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = uncertainty,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
        }

        signal.relevance?.let { relevance ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.today_signal_relevance, relevance),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SignalMaturityPill(value: String) {
    val color = signalMaturityColor(value)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.13f),
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun SignalEvidenceMeter(strength: Int, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < strength.coerceIn(0, 3)) {
                            accent
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)
                        },
                    ),
            )
        }
    }
}

private fun signalMaturityColor(value: String): Color {
    val normalized = value.lowercase()
    return when {
        "第三方" in value || "verified" in normalized -> Color(0xFF23866A)
        "仅发布" in value || "vendor" in normalized || "claim" in normalized ->
            SignalVendorClaimColor
        else -> Color(0xFF177F98)
    }
}

private fun resolveTodayDailyPlan(
    card: TodayCardEntity,
    phoneScheduleCards: List<TodayCardEntity>,
    nowMillis: Long,
): TodayDailyPlanDetails? {
    val details = parseTodayDailyPlan(card) ?: return null
    val phoneSchedule = phoneScheduleFallbackLines(
        cards = phoneScheduleCards,
        nowMillis = nowMillis,
        isTomorrow = details.isTomorrow,
    )
    return details.withPhoneScheduleFallback(phoneSchedule)
}

@Composable
private fun DailyPlanPanel(
    details: TodayDailyPlanDetails,
    accent: Color,
    weatherVisualWeight: Float,
    expanded: Boolean = false,
) {
    val weatherAccent = if (details.weatherIsRainy) Color(0xFF3E78A8) else Color(0xFFB67B18)
    val weatherAlpha = (weatherVisualWeight * 0.09f).coerceIn(0.035f, 0.09f)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(17.dp),
            color = accent.copy(alpha = 0.08f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.14f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.EventAvailable,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = details.focusHeading,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                    Spacer(Modifier.height(4.dp))
                    DailyPlanBody(
                        text = details.focus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        expanded = expanded,
                    )
                }
            }
        }

        details.weather?.let { weather ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
                color = weatherAccent.copy(alpha = weatherAlpha),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = if (details.weatherIsRainy) {
                            Icons.Filled.Umbrella
                        } else {
                            Icons.Filled.WbSunny
                        },
                        contentDescription = null,
                        tint = weatherAccent.copy(alpha = 0.60f + weatherVisualWeight * 0.40f),
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = details.weatherHeading.orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = weatherAccent,
                        )
                        val parsedWeather = details.weatherDetails
                        if (parsedWeather?.isStructured == true) {
                            Spacer(Modifier.height(7.dp))
                            WeatherMetricRow(
                                weather = parsedWeather,
                                accent = weatherAccent,
                            )
                            parsedWeather.detail?.let { detail ->
                                Spacer(Modifier.height(7.dp))
                                DailyPlanBody(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    expanded = expanded,
                                )
                            }
                        } else {
                            Spacer(Modifier.height(2.dp))
                            DailyPlanBody(
                                text = weather,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                expanded = expanded,
                            )
                        }
                    }
                }
            }
        }

        details.advice?.let { advice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.065f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = details.adviceHeading.orEmpty(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                    Spacer(Modifier.height(2.dp))
                    DailyPlanBody(
                        text = advice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        expanded = expanded,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherMetricRow(
    weather: TodayWeatherDetails,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WeatherMetric(
            label = stringResource(R.string.today_weather_location),
            value = weather.location.orEmpty(),
            accent = accent,
            modifier = Modifier.weight(1f),
        )
        WeatherMetric(
            label = stringResource(R.string.today_weather_condition),
            value = weather.condition.orEmpty(),
            accent = accent,
            modifier = Modifier.weight(1f),
        )
        WeatherMetric(
            label = stringResource(R.string.today_weather_temperature),
            value = weather.temperature.orEmpty(),
            accent = accent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WeatherMetric(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.09f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DailyPlanBody(
    text: String,
    style: TextStyle,
    color: Color,
    expanded: Boolean,
) {
    val lines = remember(text, expanded) {
        text.lineSequence()
            .mapNotNull(::normalizeDailyPlanLine)
            .let { sequence -> if (expanded) sequence else sequence.take(4) }
            .toList()
    }
    if (lines.size <= 1) {
        Text(
            text = lines.firstOrNull() ?: text,
            style = style,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.55f)),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = line,
                        style = style,
                        color = color,
                        modifier = Modifier.weight(1f),
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingMailPanel(text: String, accent: Color) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
    ) {
        Row(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Icon(
                imageVector = Icons.Filled.Inbox,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.today_card_pending_mail),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                )
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummarySectionList(sections: List<TodayCardSummarySection>) {
    sections.forEach { section ->
        Spacer(Modifier.height(10.dp))
        section.heading?.let { heading ->
            SectionTitle(heading)
            Spacer(Modifier.height(5.dp))
        }
        Text(
            text = section.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContactHighlightCard(
    fact: TodayCardFact,
    accent: Color,
    onOpen: (() -> Unit)?,
) {
    val titleParts = fact.title.split("：", limit = 2)
    val contactName = titleParts.first().trim()
    val topic = titleParts.getOrNull(1)?.trim().orEmpty()
    val modifier = if (onOpen != null) {
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
    } else {
        Modifier.fillMaxWidth()
    }
    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = accent.copy(alpha = 0.28f),
            shape = RoundedCornerShape(16.dp),
        ),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.09f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = accent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = contactName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contactName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (topic.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                fact.excerpt?.let { excerpt ->
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = excerpt,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onOpen != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FactCard(
    index: Int,
    fact: TodayCardFact,
    accent: Color,
    onOpen: (() -> Unit)?,
) {
    val modifier = if (onOpen != null) {
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
    } else {
        Modifier.fillMaxWidth()
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.width(25.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fact.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                fact.excerpt?.let { excerpt ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = excerpt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onOpen != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (fact.url != null) {
                        Icons.AutoMirrored.Filled.OpenInNew
                    } else {
                        Icons.Filled.ChevronRight
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AdvicePanel(text: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.09f),
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.today_card_advice),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TodayCardMenu(
    expanded: Boolean,
    card: TodayCardEntity,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onFeedback: (String) -> Unit,
    onClose: () -> Unit,
    sourceUrl: String?,
    onOpenSource: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val hasPrimaryActions = sourceUrl != null || card.supportsScheduleReminder()
        sourceUrl?.let { url ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.today_card_open_source)) },
                onClick = {
                    onDismiss()
                    onOpenSource(url)
                },
            )
        }
        if (card.supportsScheduleReminder()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.today_card_snooze)) },
                onClick = {
                    onDismiss()
                    onSnooze()
                },
            )
        }
        if (hasPrimaryActions) HorizontalDivider()
        FeedbackMenuItems(
            showPoorSource = card.isNewsRecommendationCard(),
            onFeedback = { feedbackType ->
                onDismiss()
                onFeedback(feedbackType)
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.today_card_dismiss)) },
            onClick = {
                onDismiss()
                onClose()
            },
        )
    }
}

@Composable
private fun TodayFeedbackMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFeedback: (String) -> Unit,
    showPoorSource: Boolean,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FeedbackMenuItems(
            showPoorSource = showPoorSource,
            onFeedback = { feedbackType ->
                onDismiss()
                onFeedback(feedbackType)
            },
        )
    }
}

@Composable
private fun FeedbackMenuItems(
    showPoorSource: Boolean,
    onFeedback: (String) -> Unit,
) {
    listOfNotNull(
        TodayFeedbackType.USEFUL to R.string.today_card_feedback_useful,
        TodayFeedbackType.IRRELEVANT to R.string.today_card_feedback_irrelevant,
        TodayFeedbackType.TOO_FREQUENT to R.string.today_card_feedback_too_frequent,
        (TodayFeedbackType.POOR_SOURCE to R.string.today_card_feedback_poor_source)
            .takeIf { showPoorSource },
    ).forEach { (type, label) ->
        DropdownMenuItem(
            text = { Text(stringResource(label)) },
            onClick = { onFeedback(type) },
        )
    }
}

@Composable
private fun todayCardCategoryName(kind: String): String = stringResource(
    when (kind) {
        "calendar" -> R.string.today_card_category_calendar
        "health" -> R.string.today_card_category_health
        "research" -> R.string.today_card_category_research
        "briefing" -> R.string.today_card_category_briefing
        "task" -> R.string.today_card_category_task
        "review" -> R.string.today_card_category_review
        "inbox" -> R.string.today_card_category_inbox
        "conversation" -> R.string.today_card_category_conversation
        "mail" -> R.string.today_card_category_mail
        "news" -> R.string.today_card_category_news
        "signal" -> R.string.today_card_category_signal
        else -> R.string.today_card_category_update
    },
)

private fun todayCardIcon(kind: String): ImageVector = when (kind) {
    "calendar" -> Icons.Filled.Event
    "health" -> Icons.Filled.Favorite
    "research" -> Icons.Filled.TravelExplore
    "briefing" -> Icons.Filled.AutoAwesome
    "task" -> Icons.Filled.TaskAlt
    "review" -> Icons.Filled.History
    "inbox" -> Icons.Filled.Inbox
    "conversation" -> Icons.AutoMirrored.Filled.Chat
    "mail" -> Icons.Filled.Email
    "news" -> Icons.Filled.Newspaper
    "signal" -> Icons.Filled.AutoAwesome
    else -> Icons.Filled.AutoAwesome
}

private fun todayCardAccent(kind: String, isPast: Boolean): Color = when {
    isPast -> Color(0xFF77808C)
    kind == "calendar" -> Color(0xFF0E9BC8)
    kind == "health" -> Color(0xFF3D9D74)
    kind == "research" -> Color(0xFF249B83)
    kind == "briefing" -> Color(0xFF3B8EDB)
    kind == "inbox" -> Color(0xFFD58B2F)
    kind == "conversation" -> Color(0xFF4D72C8)
    kind == "mail" -> Color(0xFFC27A22)
    kind == "news" -> Color(0xFF188C78)
    kind == "signal" -> Color(0xFF147E91)
    else -> Color(0xFF5B8DEF)
}

private val PendingMailAccent = Color(0xFFC64D35)
private val SignalVendorClaimColor = Color(0xFFB6761B)

@Composable
private fun todayCardActionLabel(
    card: TodayCardEntity,
    isPast: Boolean,
    isUpcoming: Boolean,
    hasExecution: Boolean,
): String {
    if (hasExecution) {
        return when (card.actionState) {
            TodayCardActionState.RUNNING -> stringResource(R.string.today_card_action_view_progress)
            TodayCardActionState.WAITING_CONFIRMATION -> stringResource(R.string.today_card_action_confirm)
            TodayCardActionState.COMPLETED -> stringResource(R.string.today_card_action_view_result)
            TodayCardActionState.FAILED -> stringResource(R.string.today_card_action_view_error)
            else -> stringResource(R.string.today_card_open_execution)
        }
    }
    if (isPast) return stringResource(R.string.today_card_action_view_record)
    if (card.riskLevel == "A3" || card.riskLevel == "A4") {
        return stringResource(R.string.today_card_action_review_and_confirm)
    }
    return when {
        card.kind == "calendar" && isUpcoming -> stringResource(R.string.today_card_action_prepare_upcoming)
        card.kind == "calendar" -> stringResource(R.string.today_card_action_prepare_today)
        card.kind == "health" -> stringResource(R.string.today_card_action_health)
        card.kind == "research" || card.kind == "briefing" ->
            stringResource(R.string.today_card_action_research)
        card.kind == "inbox" -> stringResource(R.string.today_card_action_inbox)
        card.kind == "conversation" -> stringResource(R.string.today_card_action_conversation)
        card.kind == "mail" -> stringResource(R.string.today_card_action_mail)
        card.kind == "news" -> stringResource(R.string.today_card_action_news)
        card.kind == "signal" -> stringResource(R.string.today_signal_view_analysis)
        card.kind == "task" -> stringResource(R.string.today_card_action_task)
        card.kind == "review" -> stringResource(R.string.today_card_action_review)
        else -> stringResource(R.string.today_card_action_default)
    }
}

private fun String.isNewsCard(): Boolean = this == "briefing" || this == "news"

@Composable
private fun todayCardAdvice(
    card: TodayCardEntity,
    isPast: Boolean,
    isUpcoming: Boolean,
): String = when {
    isPast -> stringResource(R.string.today_card_advice_past)
    card.kind == "calendar" && isUpcoming ->
        stringResource(R.string.today_card_advice_upcoming)
    card.kind == "calendar" -> stringResource(R.string.today_card_advice_calendar)
    card.kind == "health" -> stringResource(R.string.today_card_advice_health)
    card.kind == "research" || card.kind == "briefing" ->
        stringResource(R.string.today_card_advice_research)
    card.kind == "inbox" -> stringResource(R.string.today_card_advice_inbox)
    card.kind == "conversation" -> stringResource(R.string.today_card_advice_conversation)
    card.kind == "mail" -> stringResource(R.string.today_card_advice_mail)
    card.kind == "news" -> stringResource(R.string.today_card_advice_news)
    card.kind == "signal" -> stringResource(R.string.today_signal_advice)
    card.kind == "review" -> stringResource(R.string.today_card_advice_review)
    else -> stringResource(R.string.today_card_advice_default)
}

@Composable
private fun todayActionStateName(state: String): String = stringResource(
    when (state) {
        TodayCardActionState.RUNNING -> R.string.today_card_action_running
        TodayCardActionState.WAITING_CONFIRMATION -> R.string.today_card_action_waiting
        TodayCardActionState.COMPLETED -> R.string.today_card_action_completed
        TodayCardActionState.FAILED -> R.string.today_card_action_failed
        else -> R.string.today_card_action_prepared
    },
)

@Composable
private fun todayActionStateColor(state: String): Color = when (state) {
    TodayCardActionState.FAILED -> MaterialTheme.colorScheme.error
    TodayCardActionState.COMPLETED -> Color(0xFF2D9664)
    TodayCardActionState.WAITING_CONFIRMATION -> Color(0xFFD78622)
    else -> MaterialTheme.colorScheme.primary
}
