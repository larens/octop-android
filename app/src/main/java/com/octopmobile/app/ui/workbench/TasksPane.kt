package com.octopmobile.app.ui.workbench

import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.db.AutomationTaskStatus
import com.octopmobile.app.ui.asText

/**
 * 自动任务页（服务端 cron，docs/protocol.md §5.5）。
 *
 * 一条任务在服务端是「触发规则 + 提示词」，所以卡片上最重要的是**规则原文**与
 * **下次 / 上次执行**；「立即执行」只是旁路触发一次，不改排期（[TasksViewModel.runNow]）。
 */
@Composable
fun TasksPane(onNeedsLogin: () -> Unit) {
    val context = LocalContext.current
    val viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory)
    val state = viewModel.uiState
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }

    val notice = state.notice?.asText()
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        viewModel.consumeNotice()
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(state.runTriggered) {
        if (!state.runTriggered) return@LaunchedEffect
        Toast.makeText(context, R.string.tasks_run_triggered, Toast.LENGTH_SHORT).show()
        viewModel.consumeNotice()
    }

    var editor by remember { mutableStateOf<TaskDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<AutomationTaskEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        PaneHeader(
            title = stringResource(R.string.tasks_title),
            addDescription = stringResource(R.string.tasks_new),
            onAdd = { editor = TaskDraft() },
            refreshing = state.syncing,
            onRefresh = viewModel::sync,
        )

        when {
            // 首次同步还没有结论：给一个小的居中指示，不用全屏遮罩（spec §12）
            !state.loaded -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            tasks.isEmpty() -> Column(modifier = Modifier.padding(16.dp)) {
                EmptyCard(
                    title = stringResource(R.string.tasks_empty_title),
                    body = when {
                        // 还没选 agent 时 cron 接口无从下手，这与「确实没有任务」要分开说
                        state.agentId == null -> stringResource(R.string.tasks_agent_missing)
                        else -> state.error?.asText() ?: stringResource(R.string.tasks_empty_body)
                    },
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        busy = state.busyId != null,
                        onRunNow = { viewModel.runNow(task.id) },
                        onToggleEnabled = {
                            viewModel.setEnabled(task.id, task.status == AutomationTaskStatus.PAUSED)
                        },
                        onEdit = {
                            editor = TaskDraft(
                                id = task.id,
                                title = task.title,
                                prompt = task.prompt,
                                scheduleRule = task.scheduleRule,
                            )
                        },
                        onDelete = { deleteTarget = task },
                    )
                }
            }
        }
    }

    editor?.let { draft ->
        TaskEditorDialog(
            initial = draft,
            onDismiss = { editor = null },
            onConfirm = { confirmed ->
                if (confirmed.id == null) {
                    viewModel.create(confirmed.title, confirmed.prompt, confirmed.scheduleRule)
                } else {
                    viewModel.update(confirmed.id, confirmed.title, confirmed.prompt, confirmed.scheduleRule)
                }
                editor = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.tasks_delete_message, target.title)) },
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

@Composable
private fun TaskCard(
    task: AutomationTaskEntity,
    busy: Boolean,
    onRunNow: () -> Unit,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val paused = task.status == AutomationTaskStatus.PAUSED
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                StatusPill(paused = paused)
            }

            Spacer(Modifier.height(8.dp))
            // 等宽字体：规则原文是给用户核对用的，字符要对得齐
            Text(
                text = task.scheduleRule,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            if (!paused && task.nextRunAt != null) {
                ScheduleLine(
                    stringResource(
                        R.string.tasks_next_run,
                        DateUtils.getRelativeTimeSpanString(task.nextRunAt).toString(),
                    ),
                )
            }
            ScheduleLine(
                task.lastRunAt?.let {
                    stringResource(
                        R.string.tasks_last_run,
                        DateUtils.getRelativeTimeSpanString(it).toString(),
                    )
                } ?: stringResource(R.string.tasks_never_run),
            )
            if (task.lastStatus == TASK_STATUS_ERROR) {
                ScheduleLine(
                    stringResource(
                        R.string.tasks_last_failed,
                        task.lastError?.takeIf { it.isNotBlank() } ?: TASK_STATUS_ERROR,
                    ),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRunNow, enabled = !busy) {
                    Text(stringResource(R.string.tasks_run_now))
                }
                TextButton(onClick = onToggleEnabled, enabled = !busy) {
                    Text(
                        stringResource(
                            if (paused) R.string.tasks_resume else R.string.tasks_pause,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = null,
                        )
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
        }
    }
}

@Composable
private fun StatusPill(paused: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (paused) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Text(
            text = stringResource(
                if (paused) R.string.tasks_status_paused else R.string.tasks_status_active,
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ScheduleLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 新建 / 编辑共用的草稿。[id] 为空即新建。 */
private data class TaskDraft(
    val id: String? = null,
    val title: String = "",
    val prompt: String = "",
    val scheduleRule: String = "",
)

/**
 * 任务编辑框。
 *
 * 触发规则**直接填服务端原文**（`cron:0 9 * * *` / `interval:3600` / `date:…` / `agently:…`）——
 * 服务端是唯一解析方，客户端再造一套规则编辑器只会多出一处不一致（docs/protocol.md §5.5）。
 */
@Composable
private fun TaskEditorDialog(
    initial: TaskDraft,
    onDismiss: () -> Unit,
    onConfirm: (TaskDraft) -> Unit,
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var prompt by remember(initial) { mutableStateOf(initial.prompt) }
    var scheduleRule by remember(initial) { mutableStateOf(initial.scheduleRule) }
    // 新建时这两个必填字段本来就是空的，一打开就标红会让人以为已经填错了。等用户动过再提示，
    // 「填了又删空」仍然能看到错误。
    var promptTouched by remember(initial) { mutableStateOf(false) }
    var scheduleTouched by remember(initial) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.id == null) R.string.tasks_new else R.string.tasks_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.tasks_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it
                        promptTouched = true
                    },
                    label = { Text(stringResource(R.string.tasks_prompt_label)) },
                    minLines = 2,
                    isError = promptTouched && prompt.isBlank(),
                    supportingText = if (promptTouched && prompt.isBlank()) {
                        { Text(stringResource(R.string.err_prompt_required)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = scheduleRule,
                    onValueChange = {
                        scheduleRule = it
                        scheduleTouched = true
                    },
                    label = { Text(stringResource(R.string.tasks_schedule_label)) },
                    placeholder = { Text(stringResource(R.string.tasks_schedule_hint)) },
                    singleLine = true,
                    isError = scheduleTouched && scheduleRule.isBlank(),
                    supportingText = {
                        Text(
                            stringResource(
                                if (scheduleTouched && scheduleRule.isBlank()) {
                                    R.string.err_schedule_required
                                } else {
                                    R.string.tasks_schedule_help
                                },
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        initial.copy(
                            title = title,
                            prompt = prompt,
                            scheduleRule = scheduleRule,
                        ),
                    )
                },
                enabled = prompt.isNotBlank() && scheduleRule.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 服务端 `last_status` 的失败取值（`ok` 之外就是失败）。 */
private const val TASK_STATUS_ERROR = "error"
