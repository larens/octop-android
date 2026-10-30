package com.octopmobile.app.ui.workbench

import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.text.format.DateUtils
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.repo.MemoryEpisode
import com.octopmobile.app.ui.asText
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private enum class MemoryPage { HOME, DIARIES, DIARY_DETAIL, PROFILE_DETAIL }

/** 记忆页：画像是「我对你的了解」，日记是智能体每天根据会话形成的一页自我记录。 */
@Composable
fun MemoryPane(
    onNeedsLogin: () -> Unit,
    onSubpageChanged: (Boolean) -> Unit = {},
) {
    val viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory)
    val state = viewModel.uiState
    val snapshot = state.snapshot
    var page by remember { mutableStateOf(MemoryPage.HOME) }
    var selectedDiaryId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }

    val selectedDiary = state.snapshot?.episodes?.firstOrNull { it.id == selectedDiaryId }
    val openDiary: (MemoryEpisode) -> Unit = {
        selectedDiaryId = it.id
        page = MemoryPage.DIARY_DETAIL
    }

    DisposableEffect(page) {
        onSubpageChanged(page != MemoryPage.HOME)
        onDispose { if (page != MemoryPage.HOME) onSubpageChanged(false) }
    }
    BackHandler(enabled = page != MemoryPage.HOME) {
        page = if (page == MemoryPage.DIARY_DETAIL) MemoryPage.DIARIES else MemoryPage.HOME
    }

    when {
        page == MemoryPage.DIARY_DETAIL && selectedDiary != null -> DiaryDetailPage(
            diary = selectedDiary,
            agentName = state.agentName,
            onBack = { page = MemoryPage.DIARIES },
        )
        page == MemoryPage.DIARIES -> DiaryListPage(
            diaries = state.snapshot?.episodes.orEmpty(),
            agentName = state.agentName,
            onBack = { page = MemoryPage.HOME },
            onOpenDiary = openDiary,
        )
        page == MemoryPage.PROFILE_DETAIL && snapshot != null -> PortraitDetailPage(
            portrait = snapshot.portrait,
            updatedAt = latestMemoryActivity(snapshot),
            onBack = { page = MemoryPage.HOME },
        )
        else -> MemoryHome(
            state = state,
            onRefresh = viewModel::refresh,
            onOpenAllDiaries = { page = MemoryPage.DIARIES },
            onOpenDiary = openDiary,
            onOpenProfile = { page = MemoryPage.PROFILE_DETAIL },
        )
    }
}

@Composable
private fun MemoryHome(
    state: MemoryUiState,
    onRefresh: () -> Unit,
    onOpenAllDiaries: () -> Unit,
    onOpenDiary: (MemoryEpisode) -> Unit,
    onOpenProfile: () -> Unit,
) {
    val recentDiaries = remember(state.snapshot?.episodes) {
        recentDiaries(
            diaries = state.snapshot?.episodes.orEmpty(),
            nowMillis = System.currentTimeMillis(),
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            MemoryHero(
                agentName = state.agentName,
                refreshing = state.refreshing,
                canRefresh = state.agentId != null,
                onRefresh = onRefresh,
            )
        }

        when {
            state.agentId == null -> item {
                PaddedEmptyCard(R.string.memory_agent_missing_title, R.string.memory_agent_missing_body)
            }
            state.snapshot == null && state.error != null -> item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    EmptyCard(
                        title = stringResource(R.string.memory_server_unavailable_title),
                        body = state.error.asText(),
                    )
                }
            }
            state.snapshot == null && state.refreshing -> item {
                PaddedEmptyCard(R.string.memory_loading_title, R.string.memory_loading_body)
            }
            state.snapshot == null -> item {
                PaddedEmptyCard(R.string.memory_server_empty_title, R.string.memory_server_empty_body)
            }
        }

        state.snapshot?.let { snapshot ->
            item {
                Box(modifier = Modifier.overlapPrevious(40.dp)) {
                    DiaryShelf(
                        diaries = recentDiaries,
                        onOpenAll = onOpenAllDiaries,
                        onOpenDiary = onOpenDiary,
                    )
                }
            }
            item {
                PortraitCard(
                    portrait = snapshot.portrait,
                    updatedAt = latestMemoryActivity(snapshot),
                    onClick = onOpenProfile,
                )
            }
        }
    }
}

@Composable
private fun MemoryHero(
    agentName: String?,
    refreshing: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    val name = agentName?.takeIf(String::isNotBlank) ?: stringResource(R.string.app_name)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(292.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        AgentHeroVideo(
            modifier = Modifier
                .fillMaxWidth()
                .height(292.dp)
                .graphicsLayer {
                    scaleX = 1.22f
                    scaleY = 1.22f
                    translationY = 26.dp.toPx()
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.68f to MaterialTheme.colorScheme.background.copy(alpha = 0.58f),
                        0.88f to MaterialTheme.colorScheme.background,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 9.dp, end = 20.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.memory_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (refreshing) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                } else if (canRefresh) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    ) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Filled.Refresh,
                                stringResource(R.string.memory_refresh),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.memory_agent_companion, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                modifier = Modifier.padding(bottom = 30.dp),
            )
        }
    }
}

@Composable
private fun AgentHeroVideo(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.graphicsLayer { clip = true },
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setImageResource(R.drawable.memory_agent_blink_soft)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    (drawable as? AnimatedImageDrawable)?.apply {
                        repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        start()
                    }
                }
            }
        },
        onRelease = { view ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (view.drawable as? AnimatedImageDrawable)?.stop()
            }
            view.setImageDrawable(null)
        },
    )
}

@Composable
private fun PortraitCard(
    portrait: com.octopmobile.app.data.repo.MemoryPortrait,
    updatedAt: Long?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.memory_profile_title,
                            portrait.preferredAddress
                                ?: stringResource(R.string.memory_profile_self),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    updatedAt?.let {
                        Text(
                            stringResource(
                                R.string.memory_profile_updated,
                                DateUtils.getRelativeTimeSpanString(it).toString(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val preview = portrait.brief.ifBlank { portrait.summary }
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f,
                    ),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                if (portrait.focus.isNotEmpty()) {
                    PortraitFacts(stringResource(R.string.memory_focus_title), portrait.focus)
                }
                if (portrait.truths.isNotEmpty()) {
                    PortraitFacts(stringResource(R.string.memory_truths_title), portrait.truths)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.memory_profile_view_detail),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PortraitDetailPage(
    portrait: com.octopmobile.app.data.repo.MemoryPortrait,
    updatedAt: Long?,
    onBack: () -> Unit,
) {
    val title = stringResource(
        R.string.memory_profile_title,
        portrait.preferredAddress ?: stringResource(R.string.memory_profile_self),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { MemoryPageHeader(title, onBack) }
        updatedAt?.let { timestamp ->
            item {
                Text(
                    stringResource(
                        R.string.memory_profile_updated,
                        DateUtils.getRelativeTimeSpanString(timestamp).toString(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (portrait.summary.isNotBlank()) {
                        Text(
                            portrait.summary,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.32f,
                            ),
                        )
                    } else {
                        if (portrait.focus.isNotEmpty()) {
                            PortraitFacts(stringResource(R.string.memory_focus_title), portrait.focus)
                        }
                        if (portrait.truths.isNotEmpty()) {
                            PortraitFacts(stringResource(R.string.memory_truths_title), portrait.truths)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitFacts(title: String, facts: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        facts.forEach { fact ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(fact, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DiaryShelf(
    diaries: List<MemoryEpisode>,
    onOpenAll: () -> Unit,
    onOpenDiary: (MemoryEpisode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.memory_diary_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.memory_diary_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (diaries.isEmpty()) {
            PaddedEmptyCard(R.string.memory_diary_empty_title, R.string.memory_diary_empty_body)
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(diaries, key = { it.id }) { diary ->
                    DiaryCard(diary = diary, onClick = { onOpenDiary(diary) })
                }
            }
            TextButton(
                onClick = onOpenAll,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.memory_diary_all))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun DiaryCard(diary: MemoryEpisode, onClick: () -> Unit) {
    val accent = diaryColor(diary)
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .width(252.dp)
            .height(218.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        accent.copy(alpha = 0.10f),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiaryDateBadge(diary.occurredAt)
                Spacer(Modifier.weight(1f))
                diary.emotion?.takeIf(String::isNotBlank)?.let { EmotionPill(it) }
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.82f),
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                diaryDisplayTitle(diary),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                diary.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().weight(1f),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiaryDateBadge(timestamp: Long?) {
    val date = timestamp?.let(::Date)
    val day = date?.let { SimpleDateFormat("d", Locale.getDefault()).format(it) } ?: "--"
    val week = date?.let { SimpleDateFormat("EEE", Locale.getDefault()).format(it) } ?: "DAY"
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            Text(
                week.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiaryListPage(
    diaries: List<MemoryEpisode>,
    agentName: String?,
    onBack: () -> Unit,
    onOpenDiary: (MemoryEpisode) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MemoryPageHeader(stringResource(R.string.memory_diary_list_title), onBack) }
        item {
            Text(
                stringResource(
                    R.string.memory_diary_list_subtitle,
                    agentName?.takeIf(String::isNotBlank) ?: stringResource(R.string.app_name),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (diaries.isEmpty()) {
            item {
                EmptyCard(
                    stringResource(R.string.memory_diary_empty_title),
                    stringResource(R.string.memory_diary_empty_body),
                )
            }
        } else {
            itemsIndexed(diaries, key = { _, diary -> "diary-list-${diary.id}" }) { index, diary ->
                val month = diaryMonth(diary.occurredAt)
                val previousMonth = diaries.getOrNull(index - 1)?.let { diaryMonth(it.occurredAt) }
                if (month.isNotBlank() && month != previousMonth) {
                    Text(
                        month,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                DiaryListItem(diary = diary, onClick = { onOpenDiary(diary) })
            }
        }
    }
}

@Composable
private fun DiaryListItem(diary: MemoryEpisode, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = RoundedCornerShape(14.dp), color = diaryColor(diary)) {
                Icon(
                    Icons.Filled.CalendarToday,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(13.dp).size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    diaryListDate(diary.occurredAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    diaryDisplayTitle(diary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    diary.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiaryDetailPage(diary: MemoryEpisode, agentName: String?, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { MemoryPageHeader(stringResource(R.string.memory_diary_detail_title), onBack) }
        item {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = diaryColor(diary)) {
                        Icon(
                            Icons.Filled.Today,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp).size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        diaryDetailDate(diary.occurredAt),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    diaryDisplayTitle(diary),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    diary.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.28f,
                )
                diary.insight?.takeIf(String::isNotBlank)?.let { insight ->
                    Spacer(Modifier.height(24.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.memory_diary_growth),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(insight, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.memory_diary_signature,
                            agentName?.takeIf(String::isNotBlank) ?: stringResource(R.string.app_name),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    diary.emotion?.takeIf(String::isNotBlank)?.let { EmotionPill(it) }
                }
            }
        }
    }
}

@Composable
private fun MemoryPageHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PaddedEmptyCard(title: Int, body: Int) {
    Box(Modifier.padding(horizontal = 16.dp)) {
        EmptyCard(stringResource(title), stringResource(body))
    }
}

@Composable
private fun EmotionPill(emotion: String) {
    val (label, color) = emotionPresentation(emotion)
    Surface(shape = RoundedCornerShape(9.dp), color = color.copy(alpha = 0.14f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

private fun latestMemoryActivity(snapshot: com.octopmobile.app.data.repo.ServerMemorySnapshot): Long? =
    (snapshot.episodes.mapNotNull { it.occurredAt } + snapshot.archives.mapNotNull { it.updatedAt }).maxOrNull()

internal fun recentDiaries(
    diaries: List<MemoryEpisode>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<MemoryEpisode> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val oldestDay = today.minusDays(6)
    return diaries.asSequence()
        .mapNotNull { diary ->
            val timestamp = diary.occurredAt ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
            if (day.isBefore(oldestDay) || day.isAfter(today)) null else diary to day
        }
        .sortedWith(
            compareByDescending<Pair<MemoryEpisode, LocalDate>> { it.second }
                .thenByDescending { it.first.occurredAt ?: Long.MIN_VALUE },
        )
        .distinctBy { it.second }
        .take(7)
        .map { it.first }
        .toList()
}

private fun diaryDisplayTitle(diary: MemoryEpisode): String = diary.title?.takeIf(String::isNotBlank)
    ?: diary.summary.substringBeforeAny('。', '.', '！', '!', '？', '?', '\n').take(30)
        .ifBlank { diary.summary.take(30) }

private fun String.substringBeforeAny(vararg delimiters: Char): String {
    val index = indexOfFirst { it in delimiters }
    return if (index >= 0) substring(0, index) else this
}

/** 向上覆盖前一项，并同步扣掉占位高度，避免后续画像下面出现同等大小的空隙。 */
private fun Modifier.overlapPrevious(distance: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlap = distance.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - overlap) {
        placeable.placeRelative(0, -overlap)
    }
}

private fun diaryListDate(timestamp: Long?): String = timestamp?.let {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
} ?: ""

private fun diaryDetailDate(timestamp: Long?): String = timestamp?.let {
    DateFormat.getDateInstance(DateFormat.LONG).format(Date(it))
} ?: ""

private fun diaryMonth(timestamp: Long?): String = timestamp?.let {
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it))
} ?: ""

@Composable
private fun diaryColor(diary: MemoryEpisode): Color {
    val seed = diary.id.hashCode().mod(3)
    return when (seed) {
        0 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        1 -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.62f)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    }
}

@Composable
private fun emotionPresentation(raw: String): Pair<String, Color> = when (raw.trim().lowercase()) {
    "neutral" -> stringResource(R.string.memory_emotion_neutral) to MaterialTheme.colorScheme.primary
    "anxious", "anxiety" -> stringResource(R.string.memory_emotion_anxious) to MaterialTheme.colorScheme.error
    "happy", "joy", "joyful" -> stringResource(R.string.memory_emotion_happy) to MaterialTheme.colorScheme.tertiary
    "sad", "sadness" -> stringResource(R.string.memory_emotion_sad) to MaterialTheme.colorScheme.primary
    "angry", "anger" -> stringResource(R.string.memory_emotion_angry) to MaterialTheme.colorScheme.error
    "frustrated", "frustration" -> stringResource(R.string.memory_emotion_frustrated) to MaterialTheme.colorScheme.error
    "calm", "peaceful" -> stringResource(R.string.memory_emotion_calm) to MaterialTheme.colorScheme.tertiary
    "excited" -> stringResource(R.string.memory_emotion_excited) to MaterialTheme.colorScheme.tertiary
    "stressed", "stress", "overwhelmed" -> stringResource(R.string.memory_emotion_stressed) to MaterialTheme.colorScheme.error
    "tired", "fatigued" -> stringResource(R.string.memory_emotion_tired) to MaterialTheme.colorScheme.onSurfaceVariant
    "grateful", "gratitude" -> stringResource(R.string.memory_emotion_grateful) to MaterialTheme.colorScheme.tertiary
    "reflective", "thoughtful" -> stringResource(R.string.memory_emotion_reflective) to MaterialTheme.colorScheme.primary
    else -> raw to MaterialTheme.colorScheme.onSurfaceVariant
}
