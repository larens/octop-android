package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.octopmobile.app.R
import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.db.ConversationTaskEntity
import com.octopmobile.app.data.remote.dto.TaskApprovalDto
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.chat.TaskApprovalCard

/**
 * One user-facing task ledger.
 *
 * The server already has three different task objects (cron, Router task, write
 * approval). They are projected here by "what the user has to do next", not by
 * the internal object that happens to own the record.
 */
@Composable
internal fun TaskCenterPane(
    state: TasksUiState,
    automationTasks: List<AutomationTaskEntity>,
    onOpenAutomation: (AutomationTaskEntity) -> Unit,
    onOpenConversation: () -> Unit,
    onApprove: (TaskApprovalDto) -> Unit,
    onReject: (TaskApprovalDto) -> Unit,
    onTaskAction: (ConversationTaskEntity, String) -> Unit,
) {
    val now = remember(state.conversationTasks) { System.currentTimeMillis() }
    val conversationGroups = remember(state.conversationTasks) {
        groupTodayTasks(state.conversationTasks, now)
    }
    val automationGroups = remember(automationTasks) {
        groupAutomationTasks(automationTasks, System.currentTimeMillis())
    }
    val hasContent = state.taskApprovals.isNotEmpty() ||
        conversationGroups.needsUser.isNotEmpty() ||
        conversationGroups.active.isNotEmpty() ||
        conversationGroups.deferred.isNotEmpty() ||
        automationTasks.isNotEmpty()
    var ignoreTarget by remember { mutableStateOf<ConversationTaskEntity?>(null) }
    val listState = rememberLazyListState()
    val needsUserTotal = state.taskApprovals.size + conversationGroups.needsUser.size
    var previousNeedsUserTotal by remember { mutableIntStateOf(needsUserTotal) }
    LaunchedEffect(needsUserTotal) {
        if (needsUserTotal > previousNeedsUserTotal) {
            listState.animateScrollToItem(0)
        }
        previousNeedsUserTotal = needsUserTotal
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            !state.loaded && !hasContent -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            !hasContent -> item {
                EmptyCard(
                    title = stringResource(
                        if (state.agentId == null) {
                            R.string.tasks_agent_missing
                        } else {
                            R.string.task_center_empty_title
                        },
                    ),
                    body = state.error?.asText()
                        ?: stringResource(
                            if (state.agentId == null) {
                                R.string.tasks_agent_missing_body
                            } else {
                                R.string.task_center_empty_body
                            },
                        ),
                )
            }
            else -> {
                if (state.taskApprovals.isNotEmpty() || conversationGroups.needsUser.isNotEmpty()) {
                    item(key = "needs-user-title") {
                        SectionTitle(
                            text = stringResource(R.string.task_center_needs_user),
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        items = state.taskApprovals,
                        key = { "approval-${it.approvalId}" },
                    ) { approval ->
                        TaskApprovalCard(
                            approval = approval,
                            busy = approval.approvalId in state.approvalBusy,
                            onApprove = { onApprove(approval) },
                            onReject = { onReject(approval) },
                        )
                    }
                    items(
                        items = conversationGroups.needsUser,
                        key = { "conversation-task-${it.taskId}" },
                    ) { task ->
                        ConversationTaskSummaryCard(
                            task = task,
                            subtitle = stringResource(R.string.task_center_needs_user_hint),
                            onClick = onOpenConversation,
                            busy = task.taskId in state.taskActionBusy,
                            onComplete = {
                                onTaskAction(task, ConversationTaskAction.COMPLETE)
                            },
                            onDefer = {
                                onTaskAction(task, ConversationTaskAction.DEFER)
                            },
                            onIgnore = { ignoreTarget = task },
                        )
                    }
                }

                if (conversationGroups.active.isNotEmpty()) {
                    item(key = "active-title") {
                        SectionTitle(
                            text = stringResource(R.string.task_center_active),
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        items = conversationGroups.active,
                        key = { "active-task-${it.taskId}" },
                    ) { task ->
                        ConversationTaskSummaryCard(
                            task = task,
                            subtitle = stringResource(R.string.task_center_active_hint),
                            onClick = onOpenConversation,
                            busy = task.taskId in state.taskActionBusy,
                            onComplete = {
                                onTaskAction(task, ConversationTaskAction.COMPLETE)
                            },
                            onDefer = {
                                onTaskAction(task, ConversationTaskAction.DEFER)
                            },
                            onIgnore = { ignoreTarget = task },
                        )
                    }
                }

                if (conversationGroups.deferred.isNotEmpty()) {
                    item(key = "deferred-title") {
                        SectionTitle(
                            text = stringResource(R.string.task_center_deferred),
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        items = conversationGroups.deferred,
                        key = { "deferred-task-${it.taskId}" },
                    ) { task ->
                        ConversationTaskSummaryCard(
                            task = task,
                            subtitle = stringResource(R.string.task_center_deferred),
                            onClick = onOpenConversation,
                            busy = task.taskId in state.taskActionBusy,
                            onComplete = {
                                onTaskAction(task, ConversationTaskAction.COMPLETE)
                            },
                            onIgnore = { ignoreTarget = task },
                        )
                    }
                }

                if (automationGroups.isNotEmpty()) {
                    item(key = "automation-title") {
                        SectionTitle(
                            text = stringResource(R.string.task_center_scheduled),
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    automationGroups.forEach { group ->
                        item(key = "automation-header-${group.bucket.name}") {
                            Text(
                                text = stringResource(group.bucket.labelRes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                            )
                        }
                        item(key = "automation-group-${group.bucket.name}") {
                            AutomationTaskGroupCard(
                                tasks = group.tasks,
                                showPausedLabel = group.bucket != AutomationTaskBucket.PAUSED,
                                onOpen = onOpenAutomation,
                            )
                        }
                    }
                }
            }
        }
    }

    ignoreTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { ignoreTarget = null },
            title = { Text(stringResource(R.string.task_action_ignore_title)) },
            text = { Text(stringResource(R.string.task_action_ignore_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        ignoreTarget = null
                        onTaskAction(task, ConversationTaskAction.IGNORE)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.task_action_ignore),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { ignoreTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Count shown in the page header, shared by the tabs and the Today entry point. */
internal fun TasksUiState.needsUserCount(): Int =
    taskApprovals.size + groupTodayTasks(conversationTasks).needsUser.size

internal fun TasksUiState.activeTaskCount(): Int =
    groupTodayTasks(conversationTasks).active.size
