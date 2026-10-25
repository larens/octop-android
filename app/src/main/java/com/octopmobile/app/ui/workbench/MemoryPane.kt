package com.octopmobile.app.ui.workbench

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MemoryItemEntity
import com.octopmobile.app.ui.threads.ThreadsViewModel

/**
 * 记忆页（docs/spec.md §23.3）。
 *
 * 全部读写都在本地库（[com.octopmobile.app.data.repo.MemoryRepository] 说明了为什么不接服务端），
 * 所以这里**没有同步按钮、没有加载态** —— 一份本地的可编辑清单，改完即生效。
 *
 * 页头用 [PaneHeader]，不带 `TopAppBar`：它只是工作台的一栏，栏间切换由底部导航负责，
 * 见 [WorkbenchScreen]。
 */
@Composable
fun MemoryPane() {
    val threadsViewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory)
    val memories by viewModel.items.collectAsStateWithLifecycle()
    val threads by threadsViewModel.threads.collectAsStateWithLifecycle()

    // 来源只存了 threadId，要显示成人能读的名字就得回头查会话标题。会话列表本来就已经
    // 在本地库里（Room 驱动），所以这一步不产生任何网络请求。
    val threadTitles = remember(threads) {
        threads.associate {
            it.threadId to (it.title?.takeIf { title -> title.isNotBlank() } ?: it.threadId)
        }
    }

    var editor by remember { mutableStateOf<MemoryDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<MemoryItemEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        PaneHeader(
            title = stringResource(R.string.memory_title),
            addDescription = stringResource(R.string.memory_new),
            onAdd = { editor = MemoryDraft() },
        )

        if (memories.isEmpty()) {
            Column(modifier = Modifier.padding(16.dp)) {
                EmptyCard(
                    title = stringResource(R.string.memory_empty_title),
                    body = stringResource(R.string.memory_empty_body),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
            // 来源查得到才显示：会话已经在本地被删掉时，这行留空比显示一个裸 id 更有用
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
            text = memoryKindLabel(kind),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
