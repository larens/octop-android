package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.octopmobile.app.R
import com.octopmobile.app.ui.AppActions
import com.octopmobile.app.ui.PendingChatStart
import com.octopmobile.app.ui.PendingHandoff
import com.octopmobile.app.ui.PendingShareHandoff
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.chat.ChatScreen
import com.octopmobile.app.ui.threads.ThreadsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.OctopApp
import com.octopmobile.app.data.mobile.ShareInboxSourceAdapter
import kotlinx.coroutines.launch

/**
 * 工作台的四个落点（docs/spec.md §23）。
 *
 * 会话、Today、任务、记忆是**同一层**的四块，所以用底部导航切换，不再互相往对方页面的
 * 顶部放跳转按钮 —— 那种「页头放兄弟页面入口」的样式在只有两三个入口时还凑合，
 * 到四块之后就会把页头变成长满按钮的工具栏。
 */
enum class WorkbenchTab { CHATS, TODAY, TASKS, MEMORY }

/** 三个内容页共用同一套页标题基线，避免顶部高度和左侧起点各不相同。 */
internal val WorkbenchPageTitleStartPadding = 16.dp
internal val WorkbenchPageTitleTopPadding = 9.dp

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
    onOpenSettings: () -> Unit,
    onNeedsLogin: () -> Unit,
    initialTab: WorkbenchTab = WorkbenchTab.TODAY,
) {
    val todayPushViewModel: TodayPushViewModel = viewModel(factory = TodayPushViewModel.Factory)
    // 会话列表 ViewModel 提到工作台这一层：主会话要在用户还没切到「会话」时就开始预热，
    // 这样点过去时聊天页、Room 查询和输入框都已经在组合里，不需要等新页面创建。
    val threadsViewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val selectedAgentId by remember(threadsViewModel) {
        derivedStateOf { threadsViewModel.uiState.selectedAgentId }
    }
    val mainConversationTarget by remember(threadsViewModel) {
        derivedStateOf { threadsViewModel.uiState.mainConversationTarget }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    todayPushViewModel.start()
                    todayPushViewModel.refreshNow()
                }
                Lifecycle.Event.ON_STOP -> todayPushViewModel.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            todayPushViewModel.start()
            todayPushViewModel.refreshNow()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            todayPushViewModel.stop()
        }
    }
    // 记住选中的那一栏：从会话页返回时应该回到离开前的那一栏，而不是弹回会话列表
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var memorySubpageOpen by rememberSaveable { mutableStateOf(false) }
    var taskComposerOpen by rememberSaveable { mutableStateOf(false) }
    var mobileDataOpen by rememberSaveable { mutableStateOf(false) }
    var shareChoice by rememberSaveable { mutableStateOf(false) }
    var chatsEntryId by rememberSaveable { mutableStateOf(0) }
    var appliedInitialTab by rememberSaveable { mutableStateOf(initialTab) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialTab) {
        // 只有路由真的换了目标栏才覆盖当前栏；从会话页返回时 initialTab 还是旧值，
        // 不能把 rememberSaveable 恢复出来的「会话」再按回 Today。
        if (appliedInitialTab != initialTab) {
            appliedInitialTab = initialTab
            tab = initialTab
        }
    }

    LaunchedEffect(tab) {
        if (tab != WorkbenchTab.MEMORY) memorySubpageOpen = false
        if (tab != WorkbenchTab.TASKS) taskComposerOpen = false
        if (tab != WorkbenchTab.TODAY) mobileDataOpen = false
    }

    val incomingShare by PendingShareHandoff.pending.collectAsStateWithLifecycle()
    val pendingHandoff by PendingHandoff.pending.collectAsStateWithLifecycle()
    LaunchedEffect(incomingShare) {
        if (incomingShare != null) shareChoice = true
    }

    LaunchedEffect(selectedAgentId) {
        threadsViewModel.primeMainConversation()
    }

    // 主会话是常驻聊天页；只有用户真的停在「会话」且没有待处理的分享/新会话时，
    // 才让它接管整屏（底部导航也由聊天页自己画，键盘动画才能和原来一致）。
    val embeddedChatActive = tab == WorkbenchTab.CHATS &&
        mainConversationTarget != null &&
        incomingShare == null &&
        pendingHandoff == null

    val openThread: (String, String) -> Unit = { agentId, threadId ->
        val target = mainConversationTarget
        if (target != null && target.agentId == agentId && target.threadId == threadId) {
            // 从 Today/任务里点回主会话时，不再压一层 chat 路由，直接切回常驻会话页。
            tab = WorkbenchTab.CHATS
        } else {
            onOpenThread(agentId, threadId)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (
                !embeddedChatActive &&
                !memorySubpageOpen &&
                !taskComposerOpen &&
                !mobileDataOpen
            ) {
                WorkbenchBottomBar(
                    current = tab,
                    onSelect = {
                        if (it == WorkbenchTab.CHATS) chatsEntryId += 1
                        tab = it
                    },
                )
            }
        },
        contentWindowInsets = if (embeddedChatActive) {
            WindowInsets(0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when (tab) {
                WorkbenchTab.CHATS -> when {
                    incomingShare != null -> Box(Modifier.fillMaxSize())

                    // 主会话已经预热好时，聊天页就是这一栏本身；只有首次解析入口或
                    // 系统手递（新建会话 / 分享）还需要这块占位/协调器。
                    mainConversationTarget == null || pendingHandoff != null ->
                        NewConversationPane(
                            entryId = chatsEntryId,
                            onOpenThread = openThread,
                            onOpenFreshThread = onOpenFreshThread,
                            onNeedsLogin = onNeedsLogin,
                        )

                    else -> Box(Modifier.fillMaxSize())
                }

                WorkbenchTab.TODAY -> when {
                    mobileDataOpen -> MobileDataPane(onBack = { mobileDataOpen = false })
                    else -> TodayPane(
                        onOpenThread = onOpenThread,
                        onReplyToTask = { card, message, threadId ->
                            PendingChatStart.arm(
                                com.octopmobile.app.ui.Handoff(
                                    text = message,
                                    action = AppActions.TASK_REPLY,
                                    todayCardId = card.id,
                                ),
                            )
                            onOpenThread(card.agentId, threadId)
                        },
                        onOpenMemory = { tab = WorkbenchTab.MEMORY },
                        onProcessMobileSignal = { prompt, todayCardId ->
                            PendingHandoff.arm(
                                com.octopmobile.app.ui.Handoff(
                                    text = prompt,
                                    action = AppActions.SHARE,
                                    todayCardId = todayCardId,
                                ),
                            )
                            tab = WorkbenchTab.CHATS
                        },
                        onNeedsLogin = onNeedsLogin,
                    )
                }

                WorkbenchTab.TASKS -> TasksPane(
                    onNeedsLogin = onNeedsLogin,
                    onComposerChanged = { taskComposerOpen = it },
                )

                WorkbenchTab.MEMORY -> MemoryPane(
                    onNeedsLogin = onNeedsLogin,
                    onSubpageChanged = { memorySubpageOpen = it },
                )

            }

            if (mainConversationTarget != null) {
                // 一直组合、只在非会话栏跳过测量：第一次切到「会话」时只剩布局和绘制，
                // 不再经历导航、ChatViewModel 初始化和首屏 Room/网络请求。
                ChatScreen(
                    agentId = mainConversationTarget!!.agentId,
                    threadId = mainConversationTarget!!.threadId,
                    onOpenThread = openThread,
                    onOpenWorkbenchTab = { next -> tab = next },
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .fillMaxSize()
                        .skipMeasureWhen(!embeddedChatActive),
                    active = embeddedChatActive,
                )
            }
        }
    }

    if (shareChoice) {
        ShareDestinationDialog(
            onAskAgent = {
                val handoff = PendingShareHandoff.take()
                shareChoice = false
                if (handoff != null) PendingHandoff.arm(handoff)
                tab = WorkbenchTab.CHATS
            },
            onSave = {
                val handoff = PendingShareHandoff.take()
                shareChoice = false
                if (handoff != null) {
                    scope.launch {
                        val container = (context.applicationContext as OctopApp).container
                        val draft = ShareInboxSourceAdapter(context.contentResolver)
                            .collect(handoff.text, handoff.uris)
                        container.mobileDataRepository().saveShare(draft)
                    }
                }
                tab = WorkbenchTab.TODAY
                mobileDataOpen = true
            },
            onDismiss = {
                PendingShareHandoff.take()
                shareChoice = false
            },
        )
    }
}

@Composable
private fun ShareDestinationDialog(onAskAgent: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_destination_title)) },
        text = { Text(stringResource(R.string.share_destination_body)) },
        confirmButton = {
            Button(onClick = onSave) { Text(stringResource(R.string.share_destination_save)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onAskAgent) {
                Text(stringResource(R.string.share_destination_ask))
            }
        },
    )
}

/**
 * 会话栏的默认入口：普通点击进入稳定主会话；Shortcuts / 分享仍显式新建会话。
 * 历史会话继续从 ChatScreen 的左侧抽屉进入（见 ChatScreen.HistoryDrawerContent）。
 */
@Composable
private fun NewConversationPane(
    entryId: Int,
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenFreshThread: (agentId: String, threadId: String) -> Unit,
    onNeedsLogin: () -> Unit,
) {
    val viewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val state = viewModel.uiState

    // 系统入口（App Shortcuts / 分享）仍显式新建会话；普通点击“会话”则进入稳定主会话。
    // `explicitEntryId` 防止从聊天页返回时把同一次入口再执行一遍。
    var explicitEntryId by rememberSaveable { mutableStateOf<Int?>(null) }
    val handoff by PendingHandoff.pending.collectAsStateWithLifecycle()
    LaunchedEffect(handoff, state.selectedAgentId, entryId) {
        if (state.selectedAgentId == null) return@LaunchedEffect
        if (handoff == null) {
            if (explicitEntryId != entryId) {
                viewModel.openMainConversation(entryId, navigate = false)
            }
            return@LaunchedEffect
        }

        explicitEntryId = entryId
        val start = PendingHandoff.take()
        if (start == null) {
            explicitEntryId = null
            viewModel.openMainConversation(entryId, navigate = false)
            return@LaunchedEffect
        }
        viewModel.markMainConversationEntryHandled(entryId)
        if (start.action != AppActions.NEW_THREAD) PendingChatStart.arm(start)
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

    LaunchedEffect(state.mainConversationNavigationRequest) {
        if (state.mainConversationNavigationRequest == 0L) return@LaunchedEffect
        val target = state.mainConversationTarget ?: return@LaunchedEffect
        viewModel.consumeMainConversationNavigation()
        if (target.fresh) {
            onOpenFreshThread(target.agentId, target.threadId)
        } else {
            onOpenThread(target.agentId, target.threadId)
        }
    }

    val entryError = state.mainConversationError
        ?: state.error.takeIf { state.selectedAgentId == null }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (entryError != null) {
            Text(
                text = entryError.asText(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                when {
                    state.selectedAgentId == null -> viewModel.retry()
                    explicitEntryId == entryId -> viewModel.createThread()
                    else -> viewModel.retryMainConversation(entryId, navigate = false)
                }
            }) {
                Text(stringResource(R.string.main_retry))
            }
        } else if (
            !state.mainConversationLoading &&
            !state.creating &&
            state.mainConversationTarget != null
        ) {
            Button(onClick = viewModel::reopenMainConversation) {
                Text(stringResource(R.string.chat_open_main_conversation))
            }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.chat_opening_main_conversation))
        }
    }
}

/**
 * 隐藏时连测量都跳过，但保留组合和 ViewModel。
 *
 * 常驻聊天页如果只是 alpha=0，仍会参与整棵布局；这里返回 0×0，切到别的 Tab 后
 * 后台会话只剩组合本身，不再为不可见的消息列表付出测量/绘制成本。
 */
private fun Modifier.skipMeasureWhen(skip: Boolean): Modifier = layout { measurable, constraints ->
    if (skip) {
        layout(0, 0) {}
    } else {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

@Composable
fun WorkbenchBottomBar(
    current: WorkbenchTab,
    onSelect: (WorkbenchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.navigationBarsPadding(),
        containerColor = Color.Transparent,
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
        WorkbenchTab.CHATS -> Icons.AutoMirrored.Outlined.Chat
        WorkbenchTab.TODAY -> Icons.Outlined.CalendarMonth
        WorkbenchTab.TASKS -> Icons.Outlined.Checklist
        WorkbenchTab.MEMORY -> Icons.Outlined.AutoAwesome
    }
