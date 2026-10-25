package com.octopmobile.app.ui.threads

import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.ui.asText

/**
 * 会话列表（docs/spec.md §11 M1）。
 *
 * 列表内容来自 Room（见 [ThreadsViewModel]），所以冷启动第一帧就有内容、不空转；
 * 骨架屏只在「本地一条会话都没有」时出现，不是全屏遮罩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsScreen(
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val state = viewModel.uiState
    val threads by viewModel.threads.collectAsStateWithLifecycle()

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }

    // 新建成功就直接进会话页：那里才有输入框，用户点「+」的意图就是「立刻下发一个任务」。
    LaunchedEffect(state.createdThreadId) {
        val threadId = state.createdThreadId ?: return@LaunchedEffect
        val agentId = state.selectedAgentId ?: return@LaunchedEffect
        viewModel.consumeCreated()
        onOpenThread(agentId, threadId)
    }

    // 判定为 webview_only 时切到兜底通道。只切一次 —— 用户从兜底页按返回键回来时不该被弹回去。
    var webFallbackOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.webviewOnly) {
        if (state.webviewOnly && !webFallbackOpened) {
            webFallbackOpened = true
            Toast.makeText(context, R.string.err_webview_only, Toast.LENGTH_LONG).show()
            onOpenWeb()
        }
    }

    val selectedAgent = state.agents.firstOrNull { it.agentId == state.selectedAgentId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedAgent?.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (state.creating) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = viewModel::createThread,
                            // 还没选中 agent 时没有可建会话的对象（首拉目录期间）
                            enabled = state.selectedAgentId != null,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.threads_new),
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 只有一个 agent 时不给切换器，省掉一行没用的界面
            if (state.agents.size > 1) {
                AgentSwitcher(
                    agents = state.agents,
                    selectedAgentId = state.selectedAgentId,
                    onSelect = viewModel::selectAgent,
                )
            }

            PullToRefreshBox(
                isRefreshing = state.syncing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    threads.isEmpty() && state.agentsLoading -> ThreadSkeleton()

                    threads.isEmpty() -> ThreadFallback(
                        message = state.error?.asText() ?: stringResource(R.string.threads_empty),
                        onRetry = viewModel::retry,
                    )

                    else -> ThreadList(threads = threads, onOpenThread = onOpenThread)
                }
            }
        }
    }
}

@Composable
private fun AgentSwitcher(
    agents: List<AgentDto>,
    selectedAgentId: String?,
    onSelect: (AgentDto) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(agents, key = { it.agentId }) { agent ->
            FilterChip(
                selected = agent.agentId == selectedAgentId,
                onClick = { onSelect(agent) },
                label = { Text(agent.name?.takeIf { it.isNotBlank() } ?: agent.agentId) },
            )
        }
    }
}

@Composable
private fun ThreadList(
    threads: List<ThreadEntity>,
    onOpenThread: (agentId: String, threadId: String) -> Unit,
) {
    // DAO 已按 `pinned DESC, lastActiveAt DESC` 排序，置顶项天然在前，这里只负责分组标题。
    val pinned = threads.filter { it.pinned }
    val others = threads.filterNot { it.pinned }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (pinned.isNotEmpty()) {
            item(key = "header-pinned") {
                GroupHeader(stringResource(R.string.threads_pinned))
            }
            items(pinned, key = { it.threadId }) { thread ->
                ThreadRow(thread) { onOpenThread(thread.agentId, thread.threadId) }
            }
            if (others.isNotEmpty()) {
                item(key = "divider") {
                    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
            }
        }

        items(others, key = { it.threadId }) { thread ->
            ThreadRow(thread) { onOpenThread(thread.agentId, thread.threadId) }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadEntity, onClick: () -> Unit) {
    val subtitle = thread.lastMessage?.takeIf { it.isNotBlank() }
        ?: thread.modelRef?.takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.title?.takeIf { it.isNotBlank() } ?: thread.threadId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = DateUtils.getRelativeTimeSpanString(thread.lastActiveAt).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 「正在跑」/「等你确认」是列表上唯一的状态提示，比时间更该被看见
            if (thread.turnActive || thread.awaitingUser) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (thread.awaitingUser) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 本地没有任何会话时的占位：骨架屏（首拉 agent 中）或一句说明 + 重试。 */
@Composable
private fun ThreadSkeleton() {
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(6) { index ->
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                SkeletonBar(widthFraction = if (index % 2 == 0) 0.5f else 0.36f, height = 16.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(widthFraction = 0.75f, height = 12.dp)
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun ThreadFallback(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.main_retry))
        }
    }
}
