package com.octopmobile.app.ui.threads

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.octopmobile.app.ui.AppActions
import com.octopmobile.app.ui.PendingChatStart
import com.octopmobile.app.ui.PendingHandoff
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.workbench.MemoryDraft
import com.octopmobile.app.ui.workbench.MemoryEditorDialog
import com.octopmobile.app.ui.workbench.MemoryViewModel

/**
 * 会话列表（docs/spec.md §11 M1）。
 *
 * 列表内容来自 Room（见 [ThreadsViewModel]），所以冷启动第一帧就有内容、不空转；
 * 骨架屏只在「本地一条会话都没有」时出现，不是全屏遮罩。
 *
 * 页头留着 `TopAppBar`：它兼着「新建会话 / 设置」两个不是页面跳转的动作。页面之间的切换
 * 已经交给工作台的底部导航（[com.octopmobile.app.ui.workbench.WorkbenchScreen]），
 * 所以这里不再有「去 Today」那个入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsScreen(
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onNeedsLogin: () -> Unit,
    autoOpenNewConversation: Boolean = false,
) {
    val context = LocalContext.current
    val viewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory)
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

    // 系统入口（App Shortcuts / 分享）都落在这一级（spec §14）：只有这里能建会话。
    // 建完再把剩下的动作转交会话页 —— 分享内容与语音/拍照都得等输入区就位才好使。
    // 还没选中 agent 时先留着不动（此时也建不出会话），超过 OneShot 的 TTL 会自然作废。
    val handoff by PendingHandoff.pending.collectAsStateWithLifecycle()
    LaunchedEffect(handoff, state.selectedAgentId) {
        if (handoff == null || state.selectedAgentId == null) return@LaunchedEffect
        val start = PendingHandoff.take() ?: return@LaunchedEffect
        if (start.action != AppActions.NEW_THREAD) PendingChatStart.arm(start)
        viewModel.createThread()
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

    // 改名 / 置顶 / 删除失败：这三个动作都在列表上就地发生，列表非空时没有人看得到 error 区域，
    // 所以单独用一次性提示接住（见 ThreadsUiState.notice）。
    val noticeText = state.notice?.asText()
    LaunchedEffect(noticeText) {
        if (noticeText == null) return@LaunchedEffect
        viewModel.consumeNotice()
        Toast.makeText(context, noticeText, Toast.LENGTH_SHORT).show()
    }

    var renameTarget by remember { mutableStateOf<ThreadEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ThreadEntity?>(null) }
    var tagTarget by remember { mutableStateOf<ThreadEntity?>(null) }
    var memoryTarget by remember { mutableStateOf<ThreadEntity?>(null) }

    var autoOpened by rememberSaveable { mutableStateOf(false) }

    // WorkBuddy 风格入口：落到「会话」栏时默认先给一条新会话，历史再由会话页左侧抽屉查看。
    LaunchedEffect(autoOpenNewConversation, state.selectedAgentId, state.creating) {
        if (!autoOpenNewConversation || autoOpened) return@LaunchedEffect
        if (state.selectedAgentId == null || state.creating) return@LaunchedEffect
        autoOpened = true
        viewModel.createThread()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PullToRefreshBox(
                // 只在用户下拉时亮自带转圈；后台同步交给下面那条细进度条（spec §12）。
                isRefreshing = state.syncing && state.pulled,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    // 本地一条会话都没有、又还没问到服务端 → 骨架屏（spec §12）。判据是
                    // threadsLoaded 而不是「agent 目录拉完没」：两者之间那段同样是「还没问到」。
                    threads.isEmpty() && !state.threadsLoaded -> ThreadSkeleton()

                    threads.isEmpty() -> ThreadFallback(
                        message = state.error?.asText() ?: stringResource(R.string.threads_empty),
                        onRetry = viewModel::retry,
                    )

                    else -> ThreadList(
                        threads = threads,
                        onOpenThread = onOpenThread,
                        onTogglePin = viewModel::togglePin,
                        onRename = { renameTarget = it },
                        onEditTags = { tagTarget = it },
                        onSaveMemory = { memoryTarget = it },
                        onDelete = { deleteTarget = it },
                    )
                }
            }

            // 同步中只给顶部一条细进度条，规格与会话页一致（spec §12 / §19 第 15 条）。
            if (state.syncing && !state.pulled) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(TOP_PROGRESS_HEIGHT),
                    trackColor = Color.Transparent,
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            thread = target,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                viewModel.rename(target.threadId, title)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteDialog(
            thread = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.delete(target.threadId)
                deleteTarget = null
            },
        )
    }

    tagTarget?.let { target ->
        // 标签存在本机库里，对话框开着的时候增删都会经 Flow 回到这里
        val tags by remember(target.threadId) { viewModel.tagsForThread(target.threadId) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
        TagEditorDialog(
            threadTitle = target.title?.takeIf { it.isNotBlank() } ?: target.threadId,
            tags = tags,
            onAdd = { tag, kind -> viewModel.addTag(target.threadId, tag, kind) },
            onRemove = { tag -> viewModel.removeTag(target.threadId, tag) },
            onDismiss = { tagTarget = null },
        )
    }

    memoryTarget?.let { target ->
        // 从会话里存记忆：来源直接记下来，标题先用会话标题占位，用户在框里改成自己想留的那句话
        MemoryEditorDialog(
            initial = MemoryDraft(
                title = target.title.orEmpty(),
                sourceThreadId = target.threadId,
            ),
            onDismiss = { memoryTarget = null },
            onConfirm = { draft ->
                memoryViewModel.save(
                    id = null,
                    kind = draft.kind,
                    title = draft.title,
                    summary = draft.summary,
                    sourceThreadId = target.threadId,
                )
                memoryTarget = null
            },
        )
    }
}

@Composable
private fun ThreadList(
    threads: List<ThreadEntity>,
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onTogglePin: (ThreadEntity) -> Unit,
    onRename: (ThreadEntity) -> Unit,
    onEditTags: (ThreadEntity) -> Unit,
    onSaveMemory: (ThreadEntity) -> Unit,
    onDelete: (ThreadEntity) -> Unit,
) {
    // DAO 已按 `pinned DESC, lastActiveAt DESC` 排序，置顶项天然在前，这里只负责分组标题。
    val pinned = threads.filter { it.pinned }
    val others = threads.filterNot { it.pinned }

    // 置顶组与其余组共用同一行渲染，回调在这里绑定一次
    val row: @Composable (ThreadEntity) -> Unit = { thread ->
        ThreadRow(
            thread = thread,
            onClick = { onOpenThread(thread.agentId, thread.threadId) },
            onTogglePin = { onTogglePin(thread) },
            onRename = { onRename(thread) },
            onEditTags = { onEditTags(thread) },
            onSaveMemory = { onSaveMemory(thread) },
            onDelete = { onDelete(thread) },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (pinned.isNotEmpty()) {
            item(key = "header-pinned") {
                GroupHeader(stringResource(R.string.threads_pinned))
            }
            items(pinned, key = { it.threadId }) { thread -> row(thread) }
            if (others.isNotEmpty()) {
                item(key = "divider") {
                    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
            }
        }

        items(others, key = { it.threadId }) { thread -> row(thread) }
    }
}

/**
 * 一行会话。点开进会话，长按出菜单（标签 / 存为记忆 / 置顶 / 改名 / 删除，docs/spec.md §11、§23.3）。
 *
 * 长按菜单挂在行内的 [Box] 上而不是屏幕级状态里：菜单要贴着被长按的那一行弹出，
 * 而且只可能有一行处于展开态，没必要把这个状态提到上层。
 */
@Composable
private fun ThreadRow(
    thread: ThreadEntity,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onEditTags: () -> Unit,
    onSaveMemory: () -> Unit,
    onDelete: () -> Unit,
) {
    val subtitle = thread.lastMessage?.takeIf { it.isNotBlank() }
        ?: thread.modelRef?.takeIf { it.isNotBlank() }
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
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

            if (thread.unreadCount > 0) {
                Spacer(Modifier.size(12.dp))
                UnreadBadge(thread.unreadCount)
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

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (thread.pinned) R.string.threads_unpin else R.string.threads_pin,
                        ),
                    )
                },
                onClick = {
                    menuOpen = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.threads_rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.threads_edit_tags)) },
                onClick = {
                    menuOpen = false
                    onEditTags()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.threads_save_memory)) },
                onClick = {
                    menuOpen = false
                    onSaveMemory()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.threads_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** 未读角标。计数是客户端自己攒的（docs/protocol.md §5.2），[UNREAD_CAP] 之上只显示 `99+`。 */
@Composable
private fun UnreadBadge(count: Int) {
    Badge(containerColor = MaterialTheme.colorScheme.primary) {
        Text(
            text = if (count > UNREAD_CAP) "$UNREAD_CAP+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun RenameDialog(
    thread: ThreadEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 以会话为 key：换一个目标时输入框要重置成新标题，而不是留着上一次的输入
    var text by remember(thread.threadId) { mutableStateOf(thread.title.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.threads_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.threads_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            // 空标题服务端也会照单全收，但那等于把标题清掉了，不如在这里拦住
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DeleteDialog(
    thread: ThreadEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.threads_delete)) },
        text = {
            Text(
                stringResource(
                    R.string.threads_delete_message,
                    thread.title?.takeIf { it.isNotBlank() } ?: thread.threadId,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.threads_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 单条会话未读计数的显示上限（`ThreadRepository` 落库时也按同一个数收敛）。 */
private const val UNREAD_CAP = 99

/** 骨架屏占位行数。 */
private const val SKELETON_ROWS = 6

/** 顶部细进度条的高度，与会话页 `ChatScreen.TOP_PROGRESS_HEIGHT` 取同一个值。 */
private val TOP_PROGRESS_HEIGHT = 2.dp

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 会话列表骨架屏（spec §12）。
 *
 * 形状与 [ThreadRow] 对齐：左边标题 + 副标题两行，右边一个短条占掉时间戳的位置；
 * 呼吸明暗的节奏和 `ChatSkeleton` 一致，免得两个页面一个在动、一个是块死板子。
 */
@Composable
private fun ThreadSkeleton() {
    val pulse = rememberInfiniteTransition(label = "threads-skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "threads-skeleton-alpha",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        repeat(SKELETON_ROWS) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBar(
                        modifier = Modifier.fillMaxWidth(if (index % 2 == 0) 0.5f else 0.36f),
                        height = 16.dp,
                        alpha = alpha,
                    )
                    Spacer(Modifier.height(8.dp))
                    SkeletonBar(
                        modifier = Modifier.fillMaxWidth(0.75f),
                        height = 12.dp,
                        alpha = alpha,
                    )
                }
                Spacer(Modifier.size(12.dp))
                SkeletonBar(modifier = Modifier.width(40.dp), height = 10.dp, alpha = alpha)
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier, height: Dp, alpha: Float) {
    Box(
        modifier = modifier
            .height(height)
            .alpha(alpha)
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
