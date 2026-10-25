package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MemoryItemEntity
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.threads.ThreadsViewModel

/**
 * Today 页（docs/spec.md §23.4）。
 *
 * 不做成普通列表：先给「等你确认 / 正在执行 / 最近推进」三个分组，再把最近沉淀的记忆摆一行。
 * 页面本身不带顶部栏 —— 它只是工作台的一栏（见 [WorkbenchScreen]）。
 */
@Composable
fun TodayPane(
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenMemory: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
    val viewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory)
    val state = viewModel.uiState
    val threads by viewModel.threads.collectAsStateWithLifecycle()
    val memories by memoryViewModel.items.collectAsStateWithLifecycle()

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }

    val active = threads.filter { it.turnActive }
    val awaiting = threads.filter { it.awaitingUser }
    val recent = threads.take(5)
    val recentMemory = memories.take(TODAY_MEMORY_LIMIT)
    val agentId = state.selectedAgentId

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TodayHeroCard(
                title = stringResource(R.string.today_hero_title),
                body = stringResource(R.string.today_hero_body),
            )
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

        item {
            SectionTitle(stringResource(R.string.today_recent_title))
        }
        if (recent.isEmpty()) {
            item {
                EmptyCard(
                    title = stringResource(R.string.today_empty_title),
                    body = state.error?.asText() ?: stringResource(R.string.today_empty_body),
                )
            }
        } else {
            items(recent, key = { it.threadId }) { thread ->
                ThreadSummaryCard(
                    thread = thread,
                    subtitle = thread.lastMessage ?: stringResource(R.string.today_recent_subtitle),
                    onClick = {
                        if (agentId != null) onOpenThread(agentId, thread.threadId)
                    },
                )
            }
        }

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
        if (recentMemory.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.today_memory_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(recentMemory, key = { "memory-${it.id}" }) { memory ->
                MemorySummaryCard(memory = memory)
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
private fun TodayHeroCard(title: String, body: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
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

/** Today 上的记忆摘要：只给标题与一行正文，整理动作留在记忆页（[MemoryPane]）。 */
@Composable
private fun MemorySummaryCard(memory: MemoryItemEntity) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = memory.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (memory.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = memory.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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
