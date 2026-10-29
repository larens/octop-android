package com.octopmobile.app.ui.workbench

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MemoryItemEntity
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.db.TodayCardLifecycle
import com.octopmobile.app.data.remote.dto.TodayFeedbackType
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.PendingTodayCard
import com.octopmobile.app.ui.threads.ThreadsViewModel
import kotlinx.coroutines.delay

/**
 * Today 页（docs/spec.md §23.4）。
 *
 * 主动事项以统一卡片流呈现，日程只保留今日完成和未来 3 天的摘要。
 * 页面本身不带顶部栏 —— 它只是工作台的一栏（见 [WorkbenchScreen]）。
 */
@Composable
fun TodayPane(
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenMemory: () -> Unit,
    onProcessMobileSignal: (String, String?) -> Unit,
    onNeedsLogin: () -> Unit,
) {
    val viewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory)
    val todayViewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory)
    val state = viewModel.uiState
    val threads by viewModel.threads.collectAsStateWithLifecycle()
    val memories by memoryViewModel.items.collectAsStateWithLifecycle()
    val todayCards by todayViewModel.cards.collectAsStateWithLifecycle()
    val todayState = todayViewModel.uiState
    val pendingTodayCard by PendingTodayCard.pending.collectAsStateWithLifecycle()
    var selectedCardId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }
    LaunchedEffect(todayState.needsLogin) {
        if (todayState.needsLogin) onNeedsLogin()
    }
    LaunchedEffect(pendingTodayCard, todayCards) {
        val cardId = pendingTodayCard?.todayCardId ?: return@LaunchedEffect
        val card = todayCards.firstOrNull { it.id == cardId } ?: return@LaunchedEffect
        PendingTodayCard.takeCardId()
        selectedCardId = card.id
        if (card.lifecycle == TodayCardLifecycle.UNREAD) todayViewModel.markRead(card)
    }
    LaunchedEffect(todayCards, selectedCardId) {
        if (selectedCardId != null && todayCards.none { it.id == selectedCardId }) {
            selectedCardId = null
        }
    }

    val active = threads.filter { it.turnActive }
    val awaiting = threads.filter { it.awaitingUser }
    val recentMemory = memories.take(TODAY_MEMORY_LIMIT)
    val agentId = state.selectedAgentId
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val groups = remember(todayCards, nowMillis) {
        groupTodayCards(todayCards, nowMillis)
    }
    val currentCards = groups.current
    val upcomingCards = groups.upcoming
    val pastCards = groups.past
    val historyCards = groups.history
    val reviewPeriod = remember(nowMillis) { todayReviewPeriod(nowMillis) }
    val completedReview = remember(todayCards, reviewPeriod.date) {
        completedScheduleHighlights(todayCards, nowMillis)
    }
    val conversationHighlights = remember(threads, nowMillis) {
        todayConversationHighlights(threads, nowMillis)
    }
    val updateHighlights = remember(todayCards, reviewPeriod.date) {
        todayUpdateHighlights(todayCards, nowMillis)
    }
    val upcomingSummary = remember(upcomingCards, nowMillis) {
        upcomingCalendarSummary(upcomingCards, nowMillis)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val feedbackMessage = feedbackMessage(todayState.feedbackType)?.let { stringResource(it) }
    LaunchedEffect(todayState.feedbackRevision) {
        if (todayState.feedbackRevision > 0 && feedbackMessage != null) {
            snackbarHostState.showSnackbar(feedbackMessage)
            todayViewModel.clearFeedbackNotice(todayState.feedbackRevision)
        }
    }
    val renderTodayCard: @Composable (TodayCardEntity, Boolean) -> Unit = { card, isPast ->
        TodayRecommendationCard(
            card = card,
            isPast = isPast,
            nowMillis = nowMillis,
            phoneScheduleCards = todayCards,
            onOpenDetail = {
                if (card.lifecycle == TodayCardLifecycle.UNREAD) todayViewModel.markRead(card)
                selectedCardId = card.id
            },
            onSnooze = { todayViewModel.snooze(card) },
            onFeedback = { todayViewModel.sendFeedback(card, it) },
            onClose = { todayViewModel.dismiss(card) },
            onOpenExecution = card.executionThreadId?.let { threadId ->
                { onOpenThread(card.agentId, threadId) }
            },
            onProcess = {
                todayViewModel.markRead(card)
                onProcessMobileSignal(card.actionPrompt, card.id)
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.17f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    TodayPageHeader(
                        currentCount = currentCards.size,
                        upcomingCount = upcomingCards.size,
                        refreshing = todayState.refreshing,
                        onRefresh = todayViewModel::refresh,
                    )
                }

                if (todayState.error != null && todayCards.isNotEmpty()) {
                    item {
                        TodaySyncNotice(
                            message = todayState.error.asText(),
                            onRetry = todayViewModel::refresh,
                        )
                    }
                }
                if (todayState.error != null && todayCards.isEmpty()) {
                    item {
                        EmptyCard(
                            title = stringResource(R.string.today_agent_cards_unavailable),
                            body = todayState.error.asText(),
                        )
                    }
                }

                if (currentCards.isNotEmpty()) {
                    item {
                        TodaySectionTitle(
                            title = stringResource(R.string.today_current_cards_title),
                            body = stringResource(R.string.today_current_cards_body),
                        )
                    }
                    items(currentCards, key = { "today-card-${it.id}" }) { card ->
                        renderTodayCard(card, false)
                    }
                }

                if (
                    completedReview.isNotEmpty() ||
                    conversationHighlights.isNotEmpty() ||
                    updateHighlights.isNotEmpty()
                ) {
                    item {
                        TodayDailyReviewCard(
                            completedSchedules = completedReview,
                            conversations = conversationHighlights,
                            updates = updateHighlights,
                            isYesterday = reviewPeriod.isYesterday,
                        )
                    }
                }

                if (upcomingCards.isNotEmpty()) {
                    item {
                        TodayUpcomingScheduleCard(upcomingSummary)
                    }
                }

                if (historyCards.isNotEmpty()) {
                    item {
                        TodaySectionTitle(
                            title = stringResource(R.string.today_history_cards_title),
                            body = stringResource(R.string.today_history_cards_body),
                        )
                    }
                    items(historyCards, key = { "today-history-${it.id}" }) { card ->
                        renderTodayCard(card, true)
                    }
                }

                if (awaiting.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(R.string.today_waiting_title))
                    }
                    items(awaiting, key = { it.threadId }) { thread ->
                        ThreadSummaryCard(
                            thread = thread,
                            subtitle = stringResource(R.string.today_waiting_subtitle),
                            onClick = {
                                if (agentId != null) onOpenThread(agentId, thread.threadId)
                            },
                        )
                    }
                }

                if (active.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(R.string.today_running_title))
                    }
                    items(active, key = { it.threadId }) { thread ->
                        ThreadSummaryCard(
                            thread = thread,
                            subtitle = stringResource(R.string.today_running_subtitle),
                            onClick = {
                                if (agentId != null) onOpenThread(agentId, thread.threadId)
                            },
                        )
                    }
                }

                if (recentMemory.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle(stringResource(R.string.today_memory_title))
                            TextButton(onClick = onOpenMemory) {
                                Text(stringResource(R.string.today_memory_more))
                            }
                        }
                    }
                    items(recentMemory, key = { "memory-${it.id}" }) { memory ->
                        TodayMemoryCard(memory = memory, onClick = onOpenMemory)
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    todayCards.firstOrNull { it.id == selectedCardId }?.let { card ->
        TodayRecommendationDetail(
            card = card,
            isPast = pastCards.any { it.id == card.id },
            nowMillis = nowMillis,
            phoneScheduleCards = todayCards,
            onDismiss = { selectedCardId = null },
            onSnooze = {
                todayViewModel.snooze(card)
                selectedCardId = null
            },
            onFeedback = { feedbackType ->
                todayViewModel.sendFeedback(card, feedbackType)
                if (
                    feedbackType == TodayFeedbackType.IRRELEVANT ||
                    feedbackType == TodayFeedbackType.TOO_FREQUENT
                ) {
                    selectedCardId = null
                }
            },
            onOpenEvidenceThread = { threadId ->
                selectedCardId = null
                onOpenThread(card.agentId, threadId)
            },
            onOpenExecution = card.executionThreadId?.let { threadId ->
                {
                    selectedCardId = null
                    onOpenThread(card.agentId, threadId)
                }
            },
            onProcess = {
                selectedCardId = null
                todayViewModel.markRead(card)
                onProcessMobileSignal(card.actionPrompt, card.id)
            },
        )
    }
}

private fun feedbackMessage(feedbackType: String?): Int? = when (feedbackType) {
    TodayFeedbackType.USEFUL -> R.string.today_feedback_confirmation_useful
    TodayFeedbackType.IRRELEVANT -> R.string.today_feedback_confirmation_irrelevant
    TodayFeedbackType.TOO_FREQUENT -> R.string.today_feedback_confirmation_too_frequent
    TodayFeedbackType.POOR_SOURCE -> R.string.today_feedback_confirmation_poor_source
    else -> null
}

@Composable
private fun TodayPageHeader(
    currentCount: Int,
    upcomingCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.today_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (currentCount > 0 || upcomingCount > 0) {
                    stringResource(R.string.today_header_summary, currentCount, upcomingCount)
                } else {
                    stringResource(R.string.today_header_clear)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(14.dp).size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.today_refresh),
                    )
                }
            }
        }
    }
}

@Composable
private fun TodaySectionTitle(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodaySyncNotice(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.today_retry))
            }
        }
    }
}

/**
 * 分栏页头。**不是** `TopAppBar` —— 它只是内容的第一行，用在没有顶部栏的任务页 / 记忆页上。
 *
 * [onAdd] 与 [onRefresh] 都是可选动作：记忆页没有「同步」，任务页才有。
 */
@Composable
fun PaneHeader(
    title: String,
    addDescription: String? = null,
    onAdd: (() -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        if (onRefresh != null) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp).size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                    )
                }
            }
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = addDescription,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ThreadSummaryCard(
    thread: ThreadEntity,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = thread.title?.takeIf { it.isNotBlank() } ?: thread.threadId,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (thread.unreadCount > 0) {
                    Text(
                        text = stringResource(R.string.today_unread_count, thread.unreadCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Today 上的记忆提炼卡：与主动建议使用同一套信息层级，整理动作留在记忆页。 */
@Composable
private fun TodayMemoryCard(memory: MemoryItemEntity, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.14f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.11f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(
                            text = memoryKindLabel(memory.kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        memory.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                text = memory.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (memory.summary.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = memory.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun EmptyCard(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Today 上最多摆几条记忆 —— 再多就变成第二个记忆页了。 */
private const val TODAY_MEMORY_LIMIT = 3
