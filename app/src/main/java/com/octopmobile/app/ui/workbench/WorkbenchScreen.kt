package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.octopmobile.app.R
import com.octopmobile.app.ui.AppActions
import com.octopmobile.app.ui.PendingChatStart
import com.octopmobile.app.ui.PendingHandoff
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.threads.ThreadsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 工作台的四个落点（docs/spec.md §23）。
 *
 * 会话、Today、任务、记忆是**同一层**的四块，所以用底部导航切换，不再互相往对方页面的
 * 顶部放跳转按钮 —— 那种「页头放兄弟页面入口」的样式在只有两三个入口时还凑合，
 * 到四块之后就会把页头变成长满按钮的工具栏。
 */
enum class WorkbenchTab { CHATS, TODAY, TASKS, MEMORY }

/**
 * 原生客户端的根界面。
 *
 * 只有这一层持有 `Scaffold` 与底部导航；四个页面自己**不带**顶部栏。子页面用
 * [consumeWindowInsets] 吃掉外层已经用掉的系统栏间距，避免状态栏高度被算两遍。
 */
@Composable
fun WorkbenchScreen(
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenFreshThread: (agentId: String, threadId: String) -> Unit,
    onNeedsLogin: () -> Unit,
    initialTab: WorkbenchTab = WorkbenchTab.CHATS,
) {
    // 记住选中的那一栏：从会话页返回时应该回到离开前的那一栏，而不是弹回会话列表
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    LaunchedEffect(initialTab) {
        tab = initialTab
    }

    Scaffold(
        bottomBar = {
            WorkbenchBottomBar(current = tab, onSelect = { tab = it })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when (tab) {
                WorkbenchTab.CHATS -> NewConversationPane(
                    onOpenFreshThread = onOpenFreshThread,
                    onNeedsLogin = onNeedsLogin,
                )

                WorkbenchTab.TODAY -> TodayPane(
                    onOpenThread = onOpenThread,
                    onOpenMemory = { tab = WorkbenchTab.MEMORY },
                    onNeedsLogin = onNeedsLogin,
                )

                WorkbenchTab.TASKS -> TasksPane(onNeedsLogin = onNeedsLogin)

                WorkbenchTab.MEMORY -> MemoryPane(onNeedsLogin = onNeedsLogin)
            }
        }
    }
}

/**
 * 会话栏的默认入口：进来就直接建一条新会话，不再经过历史列表页。
 * 历史会话全部收进 ChatScreen 的左侧抽屉（见 ChatScreen.HistoryDrawerContent）。
 */
@Composable
private fun NewConversationPane(
    onOpenFreshThread: (agentId: String, threadId: String) -> Unit,
    onNeedsLogin: () -> Unit,
) {
    val viewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val state = viewModel.uiState

    // 系统入口（App Shortcuts / 分享）统一落在「会话」栏这一级（spec §14）：这里就是
    // 新建会话的地方。把动作先转交会话页 —— 分享内容与语音/拍照都得等输入区就位才好使；
    // NEW_THREAD 本身就是「开一条空会话」，到此为止。
    // `startRequested` 保证「进来这一次」只建一条：成功就导航走，失败停在错误 + 重试；
    // 切走再切回（还在同一个返回栈项里）也不再补建，避免切个 tab 就多出一条空会话。
    var startRequested by rememberSaveable { mutableStateOf(false) }
    val handoff by PendingHandoff.pending.collectAsStateWithLifecycle()
    LaunchedEffect(handoff, state.selectedAgentId) {
        if (startRequested || state.selectedAgentId == null) return@LaunchedEffect
        startRequested = true
        // 有交接就带上（NEW_THREAD 本身就是开空会话，到此为止）；没有就只建新会话。
        val start = PendingHandoff.take()
        if (start != null && start.action != AppActions.NEW_THREAD) PendingChatStart.arm(start)
        viewModel.createThread()
    }

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }

    LaunchedEffect(state.createdThreadId) {
        val threadId = state.createdThreadId ?: return@LaunchedEffect
        val agentId = state.selectedAgentId ?: return@LaunchedEffect
        viewModel.consumeCreated()
        onOpenFreshThread(agentId, threadId)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.error != null) {
            Text(
                text = state.error.asText(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                // agent 还没定（拉 agent 目录失败）就重试拉取；定了就重试建会话。
                if (state.selectedAgentId != null) viewModel.createThread() else viewModel.retry()
            }) {
                Text(stringResource(R.string.main_retry))
            }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.threads_creating))
        }
    }
}

@Composable
fun WorkbenchBottomBar(
    current: WorkbenchTab,
    onSelect: (WorkbenchTab) -> Unit,
) {
    NavigationBar(
        modifier = Modifier
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        WorkbenchTab.entries.forEach { item ->
            NavigationBarItem(
                selected = item == current,
                onClick = { onSelect(item) },
                icon = { Icon(imageVector = item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}

fun WorkbenchTab.routeValue(): String = when (this) {
    WorkbenchTab.CHATS -> "chats"
    WorkbenchTab.TODAY -> "today"
    WorkbenchTab.TASKS -> "tasks"
    WorkbenchTab.MEMORY -> "memory"
}

fun workbenchTabOf(raw: String?): WorkbenchTab = when (raw) {
    "today" -> WorkbenchTab.TODAY
    "tasks" -> WorkbenchTab.TASKS
    "memory" -> WorkbenchTab.MEMORY
    else -> WorkbenchTab.CHATS
}


private val WorkbenchTab.labelRes: Int
    get() = when (this) {
        WorkbenchTab.CHATS -> R.string.tab_chats
        WorkbenchTab.TODAY -> R.string.today_title
        WorkbenchTab.TASKS -> R.string.tasks_title
        WorkbenchTab.MEMORY -> R.string.memory_title
    }

/** 这四个图标都在 `material-icons-core` / `material-icons-extended` 里现成可用。 */
private val WorkbenchTab.icon: ImageVector
    get() = when (this) {
        WorkbenchTab.CHATS -> Icons.AutoMirrored.Filled.Chat
        WorkbenchTab.TODAY -> Icons.Filled.Today
        WorkbenchTab.TASKS -> Icons.Filled.TaskAlt
        WorkbenchTab.MEMORY -> Icons.Filled.Psychology
    }
