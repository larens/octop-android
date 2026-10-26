package com.octopmobile.app.ui.workbench

import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MemoryItemEntity
import com.octopmobile.app.data.repo.MemoryArchiveItem
import com.octopmobile.app.data.repo.MemoryEpisode
import com.octopmobile.app.data.repo.MemoryPortrait
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.threads.ThreadsViewModel

/** 记忆页：服务端档案为主，本地手工补充为辅。 */
@Composable
fun MemoryPane(onNeedsLogin: () -> Unit) {
    val threadsViewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory)
    val state = viewModel.uiState
    val memories by viewModel.items.collectAsStateWithLifecycle()
    val threads by threadsViewModel.threads.collectAsStateWithLifecycle()

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }

    // 来源只存了 threadId，要显示成人能读的名字就得回头查会话标题。会话列表本来就在本地库里。
    val threadTitles = remember(threads) {
        threads.associate {
            it.threadId to (it.title?.takeIf { title -> title.isNotBlank() } ?: it.threadId)
        }
    }

    var editor by remember { mutableStateOf<MemoryDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<MemoryItemEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PaneHeader(
                title = stringResource(R.string.memory_title),
                addDescription = stringResource(R.string.memory_new),
                onAdd = { editor = MemoryDraft() },
                refreshing = state.refreshing,
                onRefresh = state.agentId?.let { { viewModel.refresh() } },
            )
        }

        when {
            state.agentId == null -> item {
                EmptyCard(
                    title = stringResource(R.string.memory_agent_missing_title),
                    body = stringResource(R.string.memory_agent_missing_body),
                )
            }

            state.snapshot == null && state.error != null -> item {
                EmptyCard(
                    title = stringResource(R.string.memory_server_unavailable_title),
                    body = state.error.asText(),
                )
            }

            state.snapshot == null && state.refreshing -> item {
                EmptyCard(
                    title = stringResource(R.string.memory_loading_title),
                    body = stringResource(R.string.memory_loading_body),
                )
            }

            state.snapshot == null -> item {
                EmptyCard(
                    title = stringResource(R.string.memory_server_empty_title),
                    body = stringResource(R.string.memory_server_empty_body),
                )
            }
        }

        state.snapshot?.let { snapshot ->
            item {
                PortraitCard(
                    snapshot = snapshot,
                    agentName = state.agentName,
                )
            }

            item {
                SectionTitle(stringResource(R.string.memory_emotion_title))
            }
            if (snapshot.episodes.isEmpty()) {
                item {
                    EmptyCard(
                        title = stringResource(R.string.memory_emotion_empty_title),
                        body = stringResource(R.string.memory_emotion_empty_body),
                    )
                }
            } else {
                items(snapshot.episodes, key = { "${it.summary}-${it.occurredAt}" }) { episode ->
                    EpisodeCard(episode)
                }
            }

            item {
                SectionTitle(stringResource(R.string.memory_archive_title))
            }
            if (snapshot.archives.isEmpty()) {
                item {
                    EmptyCard(
                        title = stringResource(R.string.memory_archive_empty_title),
                        body = stringResource(R.string.memory_archive_empty_body),
                    )
                }
            } else {
                items(snapshot.archives, key = { "${it.kind}-${it.title}-${it.updatedAt}" }) { item ->
                    ArchiveCard(item)
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.memory_manual_title))
        }
        if (memories.isEmpty()) {
            item {
                EmptyCard(
                    title = stringResource(R.string.memory_empty_title),
                    body = stringResource(R.string.memory_empty_body),
                )
            }
        } else {
            items(memories, key = { it.id }) { memory ->
                MemoryCard(
                    memory = memory,
                    sourceTitle = memory.sourceThreadId?.let { threadTitles[it] },
                    onEdit = { editor = memory.toDraft() },
                    onDelete = { deleteTarget = memory },
                )
            }
        }
    }

    editor?.let { draft ->
        MemoryEditorDialog(
            initial = draft,
            onDismiss = { editor = null },
            onConfirm = { confirmed ->
                viewModel.save(
                    id = confirmed.id,
                    kind = confirmed.kind,
                    title = confirmed.title,
                    summary = confirmed.summary,
                    sourceThreadId = confirmed.sourceThreadId,
                )
                editor = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.memory_delete_message, target.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 编辑已有条目时把全部字段（含来源）回填进草稿，来源在 [MemoryViewModel.save] 里不会被改动。 */
private fun MemoryItemEntity.toDraft(): MemoryDraft = MemoryDraft(
    id = id,
    kind = kind,
    title = title,
    summary = summary,
    sourceThreadId = sourceThreadId,
)

@Composable
private fun PortraitCard(
    snapshot: com.octopmobile.app.data.repo.ServerMemorySnapshot,
    agentName: String?,
) {
    val portrait = snapshot.portrait
    val updatedAt = latestMemoryActivity(snapshot)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.memory_profile_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val subtitle = agentName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.memory_profile_subtitle_fallback)
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            if (portrait.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = portrait.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            updatedAt?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.memory_profile_updated,
                        DateUtils.getRelativeTimeSpanString(it).toString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.height(16.dp))
            StatsRow(
                stats = listOf(
                    stringResource(R.string.memory_profile_stat_focus, portrait.focus.size),
                    stringResource(R.string.memory_profile_stat_emotions, snapshot.episodes.size),
                    stringResource(R.string.memory_profile_stat_archives, snapshot.archives.size),
                ),
            )
            if (portrait.focus.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                BulletGroup(
                    title = stringResource(R.string.memory_focus_title),
                    items = portrait.focus,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (portrait.truths.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                BulletGroup(
                    title = stringResource(R.string.memory_truths_title),
                    items = portrait.truths,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatsRow(stats: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stats.forEach { stat ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
            ) {
                Text(
                    text = stat,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BulletGroup(
    title: String,
    items: List<String>,
    color: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
        items.forEach { item ->
            Text(
                text = "\u2022 $item",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun EpisodeCard(episode: MemoryEpisode) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                episode.emotion?.takeIf { it.isNotBlank() }?.let {
                    EmotionPill(it)
                }
                Spacer(Modifier.weight(1f))
                episode.occurredAt?.let {
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(it).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = episode.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            episode.intensity?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.memory_emotion_intensity, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EmotionPill(emotion: String) {
    val (label, color) = emotionPresentation(emotion)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ArchiveCard(item: MemoryArchiveItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.kind?.takeIf { it.isNotBlank() }?.let { KindPill(kind = it) }
                Spacer(Modifier.weight(1f))
                item.updatedAt?.let {
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(it).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
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
private fun MemoryCard(
    memory: MemoryItemEntity,
    sourceTitle: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindPill(kind = memory.kind)
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            Text(
                text = memory.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (memory.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = memory.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (sourceTitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.memory_source, sourceTitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun KindPill(kind: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = when (kind) {
                "person" -> stringResource(R.string.memory_kind_person)
                "project" -> stringResource(R.string.memory_kind_project)
                "preference" -> stringResource(R.string.memory_kind_preference)
                "commitment" -> stringResource(R.string.memory_kind_commitment)
                "entity" -> stringResource(R.string.memory_kind_entity)
                "profile" -> stringResource(R.string.memory_kind_profile)
                else -> kind
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun latestMemoryActivity(snapshot: com.octopmobile.app.data.repo.ServerMemorySnapshot): Long? =
    (snapshot.episodes.mapNotNull { it.occurredAt } + snapshot.archives.mapNotNull { it.updatedAt })
        .maxOrNull()

@Composable
private fun emotionPresentation(raw: String): Pair<String, Color> {
    val normalized = raw.trim().lowercase()
    return when (normalized) {
        "neutral" -> stringResource(R.string.memory_emotion_neutral) to MaterialTheme.colorScheme.primary
        "anxious", "anxiety" -> stringResource(R.string.memory_emotion_anxious) to MaterialTheme.colorScheme.error
        "happy", "joy", "joyful" -> stringResource(R.string.memory_emotion_happy) to MaterialTheme.colorScheme.tertiary
        "sad", "sadness" -> stringResource(R.string.memory_emotion_sad) to MaterialTheme.colorScheme.primary
        "angry", "anger", "frustrated" -> stringResource(R.string.memory_emotion_angry) to MaterialTheme.colorScheme.error
        "calm", "peaceful" -> stringResource(R.string.memory_emotion_calm) to MaterialTheme.colorScheme.tertiary
        "excited" -> stringResource(R.string.memory_emotion_excited) to MaterialTheme.colorScheme.tertiary
        "stressed", "stress", "overwhelmed" -> stringResource(R.string.memory_emotion_stressed) to MaterialTheme.colorScheme.error
        "tired", "fatigued" -> stringResource(R.string.memory_emotion_tired) to MaterialTheme.colorScheme.onSurfaceVariant
        "grateful", "gratitude" -> stringResource(R.string.memory_emotion_grateful) to MaterialTheme.colorScheme.tertiary
        else -> raw to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
