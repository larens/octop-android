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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    onOpenDetail: () -> Unit,
    onProcess: () -> Unit,
    onOpenExecution: (() -> Unit)?,
    onSnooze: () -> Unit,
    onFeedback: (String) -> Unit,
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
    val summaryPreview = remember(card.summary) { todayCardSummaryPreview(card.summary) }
    val structuredAdvice = summarySections.firstOrNull {
        it.heading == "处理建议" || it.heading == "改善建议"
    }
    val hasStructuredAdvice = structuredAdvice != null
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
            card.kind == "health" -> emptyList()
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
                    label = if (hasPendingMail) {
                        stringResource(R.string.today_card_pending_mail)
                    } else {
                        null
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
                        onDismiss = { menuExpanded = false },
                        onSnooze = onSnooze,
                        onFeedback = onFeedback,
                        sourceUrl = card.sourceUrl,
                        onOpenSource = { url -> uriHandler.openUri(url) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (calendar == null && summaryPreview.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = summaryPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (card.kind == "health") 12 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (healthVisualization != null) {
                Spacer(Modifier.height(12.dp))
                HealthVisualPanel(healthVisualization, accent)
            }

            if (calendar != null) {
                Spacer(Modifier.height(12.dp))
                CalendarFocusPanel(calendar, accent)
            } else if (hasPendingMail) {
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

            if (!hasStructuredAdvice) {
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
                opensFullNewsList -> onOpenDetail
                structuredEmail -> onOpenDetail
                onOpenExecution != null -> onOpenExecution
                isPast -> onOpenDetail
                else -> onProcess
            }
            if (structuredEmail || !executionCompleted) {
                Spacer(Modifier.height(12.dp))
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
                            structuredEmail -> Icons.Filled.ChevronRight
                            isPast -> Icons.Filled.History
                            else -> Icons.Filled.AutoAwesome
                        },
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (opensFullNewsList) {
                            stringResource(R.string.today_card_view_all_news, newsFacts.size)
                        } else if (structuredEmail) {
                            stringResource(
                                if (hasPendingMail) {
                                    R.string.today_card_action_view_pending
                                } else {
                                    R.string.today_card_action_view_details
                                },
                            )
                        } else {
                            todayCardActionLabel(
                                card = card,
                                isPast = isPast,
                                isUpcoming = isUpcoming,
                                hasExecution = onOpenExecution != null,
                            )
                        },
                    )
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
                text = stringResource(R.string.today_daily_review_title),
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
                text = stringResource(R.string.today_daily_review_advice),
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
    val hasStructuredAdvice = summarySections.any {
        it.heading == "处理建议" || it.heading == "改善建议"
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
                            label = if (hasPendingMail) {
                                stringResource(R.string.today_card_pending_mail)
                            } else {
                                null
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
                            text = card.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        if (healthVisualization != null) {
                            Spacer(Modifier.height(12.dp))
                            HealthVisualPanel(healthVisualization, accent)
                        }
                        if (calendar == null && sectionsBeforeFacts.isNotEmpty()) {
                            SummarySectionList(sectionsBeforeFacts)
                        }
                        if (calendar != null) {
                            Spacer(Modifier.height(14.dp))
                            CalendarFocusPanel(calendar, accent)
                        }

                        if (
                            calendar == null &&
                            contactFacts.isNotEmpty() &&
                            (!structuredEmail || hasPendingMail)
                        ) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle(
                                if (card.kind.isNewsCard()) {
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

                        if (calendar == null && sectionsAfterFacts.isNotEmpty()) {
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
                        IconButton(onClick = onSnooze) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = stringResource(R.string.today_card_snooze),
                            )
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
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(0.42f)) {
                Text(
                    text = details.day,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                details.time?.let { time ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(38.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(0.58f)) {
                details.eventType?.let { eventType ->
                    Text(
                        text = eventType,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                details.location?.let { location ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = location,
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
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onFeedback: (String) -> Unit,
    sourceUrl: String?,
    onOpenSource: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        sourceUrl?.let { url ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.today_card_open_source)) },
                onClick = {
                    onDismiss()
                    onOpenSource(url)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.today_card_snooze)) },
            onClick = {
                onDismiss()
                onSnooze()
            },
        )
        HorizontalDivider()
        FeedbackMenuItems(onFeedback)
    }
}

@Composable
private fun TodayFeedbackMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FeedbackMenuItems(onFeedback)
    }
}

@Composable
private fun FeedbackMenuItems(onFeedback: (String) -> Unit) {
    listOf(
        TodayFeedbackType.USEFUL to R.string.today_card_feedback_useful,
        TodayFeedbackType.IRRELEVANT to R.string.today_card_feedback_irrelevant,
        TodayFeedbackType.TOO_FREQUENT to R.string.today_card_feedback_too_frequent,
        TodayFeedbackType.POOR_SOURCE to R.string.today_card_feedback_poor_source,
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
    else -> Color(0xFF5B8DEF)
}

private val PendingMailAccent = Color(0xFFC64D35)

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
