package com.octopmobile.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.data.attachments.AttachmentRefs
import com.octopmobile.app.data.db.AttachmentEntity
import com.octopmobile.app.data.db.CompactMarkerEntity
import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.MessageStatus
import com.octopmobile.app.data.db.ThreadEntity
import com.octopmobile.app.data.model.HitlPending
import com.octopmobile.app.data.model.Part
import com.octopmobile.app.data.model.PartType
import com.octopmobile.app.data.model.PartsCodec
import com.octopmobile.app.data.model.UploadedAttachment
import com.octopmobile.app.data.remote.dto.AgentDto
import com.octopmobile.app.data.remote.dto.HitlDecision
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.service.TurnService
import com.octopmobile.app.ui.AppActions
import com.octopmobile.app.ui.PendingChatStart
import com.octopmobile.app.ui.asText
import com.octopmobile.app.ui.threads.ThreadsViewModel
import com.octopmobile.app.ui.workbench.WorkbenchBottomBar
import com.octopmobile.app.ui.workbench.WorkbenchTab
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 单会话页（docs/spec.md §18 M1–M3）。
 *
 * M1 做「看」：历史分页 + `token` / `reasoning` 流式渲染 + 连接状态。
 * M2 做「写」：输入栏发 `user_turn`、回合中置灰并可 `cancel`、附件上传、工具调用折叠卡、
 * HITL 确认卡（挂起期间输入栏同样禁用 —— 服务端会以 `hitl_paused` 拒绝新回合）。
 * M3 做「手感」：气泡骨架屏 + 顶部细进度条（去掉全屏转圈）、底部 32dp 死区的滚动跟随。
 *
 * 消息正文存在 Room 里，这里只负责把 `parts_json` 解析成气泡；解析结果按 `partsJson`
 * 记忆化，所以流式期间只有最后一条消息在重新解析（spec §12）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    agentId: String,
    threadId: String,
    onBack: () -> Unit,
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenWorkbenchTab: (WorkbenchTab) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(agentId, threadId))
    val threadsViewModel: ThreadsViewModel = viewModel(factory = ThreadsViewModel.Factory)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reconnect()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 离开会话页就意味着连接要断（ViewModel 被清、socket 关掉），通知没有理由继续挂着。
            TurnService.stop(context)
            // onCleared 里 viewModelScope 已取消，空会话清理必须赶在它之前发出。
            viewModel.deleteUntouchedAutoConversation()
        }
    }

    val state = viewModel.uiState
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val thread by viewModel.thread.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val turnActive by viewModel.turnActive.collectAsStateWithLifecycle()
    val pending by viewModel.hitl.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val cachedAttachments by viewModel.cachedAttachments.collectAsStateWithLifecycle()
    val compactMarkers by viewModel.compactMarkers.collectAsStateWithLifecycle()
    val mentionable by viewModel.mentionable.collectAsStateWithLifecycle()
    val threadListState = threadsViewModel.uiState
    val historyThreads by threadsViewModel.threads.collectAsStateWithLifecycle()
    // 系统入口（快捷方式 / 分享）带进来的动作，见下面的 LaunchedEffect（spec §14）。
    val chatStart by PendingChatStart.pending.collectAsStateWithLifecycle()

    // 消息与压缩标记合成一条渲染序列，标记插在「第一条晚于它的消息」之前（spec §8.2）。
    val rows = remember(messages, compactMarkers) { buildChatRows(messages, compactMarkers) }

    // 挂起期间服务端会拒绝新回合（`SteerRejectReason.HITL_PAUSED`），所以和「回合中」一样禁用输入。
    val blocked = turnActive || pending != null

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(context, uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.attach(context, uri)
    }
    val voiceNoteRecorder = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        viewModel.attach(context, uri)
    }

    // 拍照快捷方式：`ACTION_IMAGE_CAPTURE` 需要一个可写的 content:// 目标，落在 cacheDir 里。
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        cameraUri = null
        if (saved && uri != null) viewModel.attach(context, uri)
    }
    fun launchCamera() {
        val uri = runCatching { newCameraUri(context) }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, R.string.err_camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    fun launchVoiceNoteRecorder() {
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        val available = intent.resolveActivity(context.packageManager) != null
        if (!available) {
            Toast.makeText(context, R.string.err_voice_note_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        voiceNoteRecorder.launch(intent)
    }

    // 清单里声明了 CAMERA，就得先拿到授权，否则 ACTION_IMAGE_CAPTURE 会被系统拒掉。
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCamera() }

    // 输入框文本：`@专家名` 直接写在正文里（spec §23.5），发送时再解析成 `target_agent_ids`。
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var inputFocused by remember { mutableStateOf(false) }

    // `@专家` 的候选面板（spec §23.5）：内容全部由输入框的当前文本与光标位置推出来，
    // 不额外存一份「已选中」状态 —— 名字就写在正文里，删掉那几个字就等于取消。
    val mentionCandidates = remember(input, mentionable) {
        mentionSuggestions(input, mentionable)
    }

    // 选中一个候选：把光标前那个没写完的 `@查询词` 换成 `@专家名 `，光标落在空格之后。
    fun pickMention(agent: AgentDto) {
        input = insertMention(input, agent)
    }

    // 快捷方式 / 分享的最后一跳：会话已由列表页建好，这里把动作补上（spec §14）。
    // `ready` 是门槛：ViewModel 的仓库是异步就绪的，早于它执行会被 `share` 静默丢掉。
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    LaunchedEffect(chatStart, ready) {
        if (!ready) return@LaunchedEffect
        val start = PendingChatStart.take() ?: return@LaunchedEffect
        when (start.action) {
            AppActions.SHARE -> viewModel.share(context, start.text, start.uris)

            AppActions.CAMERA -> if (hasPermission(context, Manifest.permission.CAMERA)) {
                launchCamera()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }

            // NEW_THREAD 到此为止：一条空会话就是终点，用户自己输入。
            else -> Unit
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val imeVisible = WindowInsets.isImeVisible
    val showBackAction = shouldShowChatBackAction(messages, thread)

    fun hideKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(threadListState.createdThreadId) {
        val created = threadListState.createdThreadId ?: return@LaunchedEffect
        val selectedAgentId = threadListState.selectedAgentId ?: return@LaunchedEffect
        threadsViewModel.consumeCreated()
        drawerState.close()
        onOpenThread(selectedAgentId, created)
    }

    // 回到前台立刻重连一次（spec §10）：退避等待对「刚恢复网络」这种情况没有意义，
    // 而用户从后台切回来正是最可能刚刚恢复网络的时候。
    // 回合进行中挂一条前台通知，切到后台不会被系统顺手杀掉（spec §14、§19 第 20 条）。
    // 跟着 turnActive 起停，而不是自己判断存活 —— 服务本身不持有连接，只负责让进程活着。
    val turnTitle = thread?.title?.takeIf { it.isNotBlank() } ?: threadId
    LaunchedEffect(turnActive, turnTitle) {
        if (turnActive) {
            TurnService.start(context, threadId, turnTitle)
        } else {
            TurnService.stop(context)
        }
    }

    // 「用户是否还在底部」是唯一的跟随开关（spec §12）：只认离底 32dp 的死区，
    // 不在死区内就不再自动贴底，并浮出「回到最新」按钮。
    val deadZone = with(LocalDensity.current) { SCROLL_DEAD_ZONE.toPx() }
    val atBottom by remember(deadZone) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // 第二个条件是「消息比一屏还短」的情况：那时最后一项不是 total-1（旧内容仍在下标里），
            // 但它就贴在底部，同样应该继续跟随。
            last.index >= info.totalItemsCount - 1 ||
                last.offset + last.size >= info.viewportEndOffset - deadZone
        }
    }

    // 「load-older」也是 LazyColumn 里的独立一项；算绝对下标时必须带上它，
    // 否则新内容到达时会停在倒数第二条上。
    val lastItemIndex = rows.lastIndex + if (state.hasMore || state.loadingOlder) 1 else 0

    // 新内容到达时才滚动，且只在用户仍处于死区内时。这里读 atBottom 的当前值（不是在
    // 组合期捕获）—— 用 snapshotFlow 监听它会变成「用户滑到底就强制贴底」，反而抢滚动。
    val stickKey = messages.size to (messages.lastOrNull()?.partsJson?.length ?: 0)
    LaunchedEffect(stickKey) {
        if (atBottom && messages.isNotEmpty()) listState.scrollToItem(lastItemIndex)
    }

    // 滑到顶部自动续拉更早的一页。这里读 viewModel.uiState 而不是捕获的 state ——
    // LaunchedEffect 的闭包只在首次组合时创建，捕获值会过期。
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val current = viewModel.uiState
                if (index <= 1 && current.hasMore && !current.loadingOlder) viewModel.loadOlder()
            }
    }

    val currentModelRef = state.selectedModelRef ?: thread?.modelRef
    val selectedModel = remember(state.modelOptions, currentModelRef) {
        state.modelOptions.firstOrNull { it.modelRef == currentModelRef }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                HistoryDrawerContent(
                    threads = historyThreads,
                    currentThreadId = threadId,
                    syncing = threadListState.syncing,
                    creating = threadListState.creating,
                    onNewConversation = {
                        if (!threadListState.creating) threadsViewModel.createThread()
                    },
                    onOpenSettings = onOpenSettings,
                    onDeleteThreads = { ids -> ids.forEach(threadsViewModel::delete) },
                    onSelectThread = { item ->
                        scope.launch {
                            drawerState.close()
                            onOpenThread(item.agentId, item.threadId)
                        }
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = thread?.title?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.chat_new_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (turnActive) {
                                Text(
                                    text = stringResource(R.string.chat_turn_active),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            hideKeyboard()
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.chat_more_actions),
                            )
                        }
                    },
                    actions = {
                        if (showBackAction) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_cancel),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(connectionColor(connection)),
                        )
                    },
                )
            },
            bottomBar = {
                Column {
                    ChatBottomBar(
                        imeVisible = imeVisible,
                        blocked = blocked,
                        pending = pending,
                        attachments = attachments,
                        busy = busy,
                        mentionSuggestions = mentionCandidates,
                        onPickMention = { agent -> pickMention(agent) },
                        canMention = mentionable.isNotEmpty(),
                        modelOptions = state.modelOptions,
                        selectedModel = selectedModel,
                        modelsLoading = state.modelsLoading,
                        onSelectModel = viewModel::selectModel,
                        onSend = viewModel::send,
                        onCancel = viewModel::cancel,
                        onUploadFile = { filePicker.launch(arrayOf("*/*")) },
                        onUploadImage = {
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onTakePhoto = {
                            if (hasPermission(context, Manifest.permission.CAMERA)) {
                                launchCamera()
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onRecordVoiceNote = { launchVoiceNoteRecorder() },
                        input = input,
                        onInputChange = { input = it },
                        inputFocused = inputFocused,
                        onInputFocusChange = { inputFocused = it },
                        onRemove = viewModel::removeAttachment,
                        onResolve = viewModel::resolveHitl,
                    )
                    if (shouldShowWorkbenchBar(imeVisible, inputFocused)) {
                        WorkbenchBottomBar(
                            current = WorkbenchTab.CHATS,
                            onSelect = { tab ->
                                if (tab != WorkbenchTab.CHATS) onOpenWorkbenchTab(tab)
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        detectTapGestures { hideKeyboard() }
                    },
            ) {
            when {
                // 首屏没有本地内容时给气泡骨架屏，不是全屏转圈（spec §12、§19 第 15 条）。
                messages.isEmpty() && state.historyLoading -> ChatSkeleton()

                messages.isEmpty() && state.error != null -> CenteredNotice(
                    text = state.error!!.asText(),
                    onRetry = viewModel::retry,
                )

                messages.isEmpty() -> CenteredNotice(
                    text = stringResource(R.string.chat_no_messages),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.hasMore || state.loadingOlder) {
                        item(key = "load-older") {
                            OlderRow(loading = state.loadingOlder, onLoad = viewModel::loadOlder)
                        }
                    }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Message -> MessageBubble(
                                entities = row.entities,
                                json = container.json,
                                threadId = threadId,
                                cachedAttachments = cachedAttachments,
                                onEnsureAttachment = viewModel::ensureAttachment,
                                // 「重新拉取该轮」= 以服务端为准重取最新一页：本地那半截行会在
                                // 历史占位清理里退场（见 ChatRepository.loadHistory）。
                                onRefetch = viewModel::retry,
                                onMessageTap = ::hideKeyboard,
                            )

                            is ChatRow.Compact -> CompactDivider()
                        }
                    }
                }
            }

            // 同步中只给顶部一条细进度条，全页唯一的「忙」提示就是它（spec §12）。
            if (state.historyLoading || state.loadingOlder) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(TOP_PROGRESS_HEIGHT),
                    trackColor = Color.Transparent,
                )
            }

            // 用户上滑离开死区后就不再自动贴底，这时给出回到最新的入口。
            if (!atBottom && messages.isNotEmpty()) {
                FilledTonalIconButton(
                    onClick = { scope.launch { listState.animateScrollToItem(lastItemIndex) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(R.string.chat_back_to_bottom),
                    )
                }
            }

            // 一次性提示排在错误之上：它通常是用户刚做的动作的回执，更需要被看到。
            val noticeText = when (val current = notice) {
                null -> null
                ChatNotice.Queued -> stringResource(R.string.chat_queued)
                ChatNotice.AttachFailed -> stringResource(R.string.chat_attach_failed)
                is ChatNotice.Failure -> current.error.asText()
            }
            if (noticeText != null) {
                OverlayNotice(
                    text = noticeText,
                    onDismiss = viewModel::clearNotice,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            val streamError = state.streamError
            if (streamError != null) {
                OverlayNotice(
                    text = streamError,
                    error = true,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        }
    }
}

// ------------------------------------------------------------------ 输入区

@Composable
private fun HistoryDrawerContent(
    threads: List<ThreadEntity>,
    currentThreadId: String,
    syncing: Boolean,
    creating: Boolean,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteThreads: (Collection<String>) -> Unit,
    onSelectThread: (ThreadEntity) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 360.dp)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onNewConversation,
            enabled = !creating,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.threads_new))
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_history_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.chat_delete_selected, selected.size))
                    }
                }
                TextButton(
                    onClick = {
                        editing = false
                        selected = emptySet()
                    },
                ) {
                    Text(stringResource(R.string.chat_history_manage_done))
                }
            } else {
                TextButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.chat_history_manage))
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (syncing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                trackColor = Color.Transparent,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (threads.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(threads, key = { it.threadId }) { item ->
                    val isCurrent = item.threadId == currentThreadId
                    HistoryDrawerRow(
                        thread = item,
                        isCurrent = isCurrent,
                        editing = editing,
                        checked = selected.contains(item.threadId),
                        onClick = {
                            if (editing && !isCurrent) {
                                selected = if (item.threadId in selected) {
                                    selected - item.threadId
                                } else {
                                    selected + item.threadId
                                }
                            } else if (!editing) {
                                onSelectThread(item)
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.chat_history_title)) },
            text = {
                Text(stringResource(R.string.chat_delete_selected_confirm, selected.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteThreads(selected)
                        selected = emptySet()
                        editing = false
                    },
                ) {
                    Text(stringResource(R.string.threads_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryDrawerRow(
    thread: ThreadEntity,
    isCurrent: Boolean,
    editing: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (checked || isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (editing) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                    enabled = !isCurrent,
                )
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = thread.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_new_title),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                thread.lastMessage?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = DateUtils.getRelativeTimeSpanString(thread.lastActiveAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editing && isCurrent) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.threads_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 底部输入栏。
 *
 * [blocked]（回合进行中或 HITL 挂起）时输入框与附件按钮置灰、发送键换成中止
 * —— M2 不做 steer，等 `turn.ended` 后才允许再发（docs/spec.md §18.1）。
 */
@Composable
private fun ChatBottomBar(
    imeVisible: Boolean,
    blocked: Boolean,
    pending: HitlPending?,
    attachments: List<UploadedAttachment>,
    busy: Boolean,
    mentionSuggestions: List<AgentDto>,
    onPickMention: (AgentDto) -> Unit,
    canMention: Boolean,
    modelOptions: List<ChatModelOption>,
    selectedModel: ChatModelOption?,
    modelsLoading: Boolean,
    onSelectModel: (String?) -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVoiceNote: () -> Unit,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    inputFocused: Boolean,
    onInputFocusChange: (Boolean) -> Unit,
    onRemove: (UploadedAttachment) -> Unit,
    onResolve: (String, String?) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .then(
                    if (shouldApplyChatBottomBarNavigationPadding(imeVisible)) {
                        Modifier.navigationBarsPadding()
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (pending != null) {
                HitlCard(pending = pending, onResolve = onResolve)
                Spacer(Modifier.height(6.dp))
            }

            // 候选面板紧贴输入框上方：它认的是「正在打的那个 `@`」，弹窗会把它和输入框隔开一屏。
            if (mentionSuggestions.isNotEmpty()) {
                MentionSuggestions(agents = mentionSuggestions, onPick = onPickMention)
                Spacer(Modifier.height(6.dp))
            }

            if (busy || attachments.isNotEmpty()) {
                AttachmentRow(
                    attachments = attachments,
                    busy = busy,
                    onRemove = onRemove,
                )
                Spacer(Modifier.height(6.dp))
            }

            InputRow(
                blocked = blocked,
                canSend = attachments.isNotEmpty(),
                canMention = canMention,
                modelOptions = modelOptions,
                selectedModel = selectedModel,
                modelsLoading = modelsLoading,
                onSelectModel = onSelectModel,
                onSend = onSend,
                onCancel = onCancel,
                onUploadFile = onUploadFile,
                onUploadImage = onUploadImage,
                onTakePhoto = onTakePhoto,
                onRecordVoiceNote = onRecordVoiceNote,
                onMention = { onInputChange(input.withMentionTrigger()) },
                input = input,
                onInputChange = onInputChange,
                onInputFocusChange = onInputFocusChange,
            )
        }
    }
}

@Composable
private fun InputRow(
    blocked: Boolean,
    canSend: Boolean,
    canMention: Boolean,
    modelOptions: List<ChatModelOption>,
    selectedModel: ChatModelOption?,
    modelsLoading: Boolean,
    onSelectModel: (String?) -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVoiceNote: () -> Unit,
    onMention: () -> Unit,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onInputFocusChange: (Boolean) -> Unit,
) {
    val sendable = !blocked && (input.text.isNotBlank() || canSend)
    var menuExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    fun submit() {
        if (!sendable) return
        val text = input.text
        onInputChange(TextFieldValue(""))
        onSend(text)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    focused = state.isFocused
                    if (!state.isFocused) modelExpanded = false
                    onInputFocusChange(state.isFocused)
                },
            enabled = !blocked,
            placeholder = { Text(stringResource(R.string.chat_input_hint_new)) },
            leadingIcon = {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !blocked,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chat_more_actions),
                    )
                }
            },
            trailingIcon = {
                if (blocked) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = { submit() },
                        enabled = sendable,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (sendable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                }
            },
            minLines = 1,
            maxLines = 3,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_upload_file)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onUploadFile()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_take_photo)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onTakePhoto()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_upload_image)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onUploadImage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_record_voice_note)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.KeyboardVoice,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onRecordVoiceNote()
                },
            )
            if (canMention) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_mention)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.AlternateEmail,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onMention()
                    },
                )
            }
        }
    }

    if (shouldShowModelSelector(focused || modelExpanded)) {
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = modelOptions.isNotEmpty() && !blocked) {
                        modelExpanded = true
                    },
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            modelsLoading -> stringResource(R.string.chat_model_loading)
                            selectedModel != null -> selectedModel.label
                            else -> stringResource(R.string.chat_model_auto)
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
            ) {
                if (selectedModel != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_model_auto)) },
                        onClick = {
                            modelExpanded = false
                            onSelectModel(null)
                        },
                    )
                }
                modelOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = option.providerName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            modelExpanded = false
                            onSelectModel(option.modelRef)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachments: List<UploadedAttachment>,
    busy: Boolean,
    onRemove: (UploadedAttachment) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.chat_uploading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        attachments.forEach { attachment ->
            AttachmentChip(
                filename = attachment.filename,
                onRemove = { onRemove(attachment) },
            )
        }
    }
}

@Composable
private fun AttachmentChip(filename: String, onRemove: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = 8.dp,
                end = if (onRemove == null) 8.dp else 0.dp,
                top = 3.dp,
                bottom = 3.dp,
            ),
        ) {
            Text(
                text = filename,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * `@专家` 候选列表（spec §23.5）。
 *
 * 摆在输入框正上方而不是弹一个对话框：面板跟着「正在打的那个 `@`」走，弹窗会把它和输入框
 * 隔开一屏，选完还得再关一次。选中后名字落进正文（`@名字`），提交时由 ViewModel 按正文
 * 还原 `target_agent_ids` —— 所以这里不做勾选态，删掉正文里的那几个字就等于取消。
 */
@Composable
private fun MentionSuggestions(agents: List<AgentDto>, onPick: (AgentDto) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
        ) {
            items(agents, key = { it.agentId }) { agent ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(agent) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AlternateEmail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = agent.displayName(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = agent.description?.takeIf { it.isNotBlank() }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 光标前是否正处在一次 `@` 提及里 —— 是的话返回查询词（`@` 之后、光标之前的文本）。
 *
 * 只在 `@` 位于词首、且它之后没敲过空白时才成立：敲了空格就说明这句提及已经写完，面板
 * 该收起来，而不是继续拿后面的正文去匹配。有选区时不猜（`@` 与光标的关系不唯一）。
 */
private fun mentionQuery(value: TextFieldValue, candidates: List<AgentDto>): String? {
    if (candidates.isEmpty() || !value.selection.collapsed) return null
    val before = value.text.substring(0, value.selection.end)
    val at = before.lastIndexOf(MENTION_PREFIX)
    if (at < 0) return null
    if (at > 0 && !before[at - 1].isWhitespace()) return null
    val query = before.substring(at + 1)
    return query.takeIf { q -> q.none { it.isWhitespace() } }
}

/** [mentionQuery] 有结果时按名字过滤出的候选，最多 [MAX_MENTION_SUGGESTIONS] 个。 */
private fun mentionSuggestions(
    value: TextFieldValue,
    candidates: List<AgentDto>,
): List<AgentDto> {
    val query = mentionQuery(value, candidates) ?: return emptyList()
    return candidates
        .filter { it.displayName().contains(query, ignoreCase = true) }
        .take(MAX_MENTION_SUGGESTIONS)
}

/** 把光标（或选区）前那个没写完的 `@查询词` 换成 `@专家名 `，光标落在空格之后。 */
private fun insertMention(value: TextFieldValue, agent: AgentDto): TextFieldValue {
    val at = value.text.substring(0, value.selection.min).lastIndexOf(MENTION_PREFIX)
    if (at < 0) return value
    val token = MENTION_PREFIX + agent.displayName() + " "
    val text = value.text.substring(0, at) + token + value.text.substring(value.selection.max)
    return TextFieldValue(text = text, selection = TextRange(at + token.length))
}

/** 在光标（或选区）处打一个 `@` —— `+` 菜单里那个入口就是用它把面板唤出来的。 */
private fun TextFieldValue.withMentionTrigger(): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val text = this.text.substring(0, start) + MENTION_PREFIX + this.text.substring(end)
    return TextFieldValue(text = text, selection = TextRange(start + MENTION_PREFIX.length))
}

/** 候选面板最多列这么多条 —— 超出部分靠继续打字收窄，不做滚动大战。 */
private const val MAX_MENTION_SUGGESTIONS = 6

/**
 * 消息里的附件（spec §13）。
 *
 * 图片在本地有文件时直接内联渲染，否则退回文件名卡片，并在渲染时**按需**去拉一次
 * —— 所以第一次打开是文件名、拉完自动变成图，第二次打开直接命中本地缓存
 * （spec §19 第 18 条）。
 *
 * 刻意不做「先显示网络图、后台再落盘」：那要求把带鉴权的 OkHttp 客户端接进 Coil 的
 * 网络层，而这里的下载本来就要走一遍自己的落盘 + 容量控制，两条路只会互相打架。
 */
@Composable
private fun AttachmentBubble(
    part: Part,
    threadId: String,
    cachedAttachments: Map<String, AttachmentEntity>,
    onEnsure: suspend (String?, String?) -> Unit,
) {
    val ref = part.url
    val id = ref?.let { AttachmentRefs.idOf(threadId, it) }
    val localPath = id?.let { cachedAttachments[it]?.localPath }
    val filename = part.filename.orEmpty()
        .ifBlank { stringResource(R.string.chat_attachment_generic) }
    val isImage = AttachmentRefs.isImage(part.mime)

    if (!isImage || localPath == null) {
        // 只有图片值得下载：非图片在这条界面上本来就只显示文件名，拉下来也无处可用。
        // 没有引用时（刚发出的本地附件、内联帧）同样无从下载。
        if (isImage && localPath == null && ref != null) {
            LaunchedEffect(id) { onEnsure(ref, part.mime) }
        }
        AttachmentChip(filename = filename, onRemove = null)
        return
    }

    AsyncImage(
        model = File(localPath),
        contentDescription = filename,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

// ------------------------------------------------------------------ HITL

/**
 * 人工确认卡。
 *
 * 上游要求**决定条数与动作数严格相等**（`hitl/coordinator.py:197-198`），所以这里用同一个
 * 决定类型覆盖卡片里的全部动作（[ChatViewModel.resolveHitl] 已按此展开）。
 *
 * 拒绝与回答都会被原样注入模型上下文，服务端校验非空 —— 输入框为空时按钮不可点。
 */
@Composable
private fun HitlCard(pending: HitlPending, onResolve: (String, String?) -> Unit) {
    var text by remember(pending) { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(
                    if (pending.isQuestion) R.string.chat_hitl_question_title else R.string.chat_hitl_title,
                ),
                style = MaterialTheme.typography.titleSmall,
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                pending.actions.forEach { action ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = action.description?.takeIf { it.isNotBlank() } ?: action.name,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val args = action.args
                    if (!args.isNullOrBlank()) {
                        Text(
                            text = args,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_hitl_reason_hint)) },
                maxLines = 4,
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending.isQuestion) {
                    Button(
                        onClick = { onResolve(HitlDecision.RESPOND, text.trim()) },
                        enabled = text.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.chat_hitl_reply))
                    }
                } else {
                    OutlinedButton(
                        onClick = { onResolve(HitlDecision.REJECT, text.trim()) },
                        enabled = text.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.chat_hitl_reject))
                    }
                    if (pending.canApprove) {
                        Button(onClick = { onResolve(HitlDecision.APPROVE, null) }) {
                            Text(stringResource(R.string.chat_hitl_approve))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 消息

@Composable
private fun OlderRow(loading: Boolean, onLoad: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onLoad) {
                Text(stringResource(R.string.chat_load_older))
            }
        }
    }
}

/**
 * 居中占位文案。**故意不带任何进度指示**：忙态一律走顶部细进度条或骨架屏
 * （spec §12、§19 第 15 条「全程不存在全屏转圈」）。
 */
@Composable
private fun CenteredNotice(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.main_retry))
            }
        }
    }
}

/**
 * 会话骨架屏（spec §12「会话用气泡占位」）。
 *
 * 首次进入会话时本地还没有任何一行可渲染，这里先给出「消息正在到达」的形状 ——
 * 形状与 [MessageBubble] 对齐，左右交错，让首屏就有方向感。
 */
@Composable
private fun ChatSkeleton() {
    val pulse = rememberInfiniteTransition(label = "chat-skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "chat-skeleton-alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(SKELETON_BUBBLES) { index ->
            // 每三条有一个「用户」气泡靠右，其余靠左，和真实消息的排布一致
            val isUser = index % 3 == 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .fillMaxWidth(if (isUser) 0.45f else 0.7f)
                        .alpha(alpha)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SkeletonLine(fraction = 1f)
                    SkeletonLine(fraction = if (index % 2 == 0) 0.7f else 0.5f)
                }
            }
        }
    }
}

@Composable
private fun SkeletonLine(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(12.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

/** 浮在内容顶部的短提示。比 Snackbar 更适合这里：不需要额外的作用域，也不会盖住输入栏。 */
@Composable
private fun OverlayNotice(
    text: String,
    error: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.inverseSurface
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.inverseOnSurface
        },
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = if (onDismiss == null) 12.dp else 0.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    entities: List<MessageEntity>,
    json: Json,
    threadId: String,
    cachedAttachments: Map<String, AttachmentEntity>,
    onEnsureAttachment: suspend (String?, String?) -> Unit,
    onRefetch: () -> Unit,
    onMessageTap: () -> Unit,
) {
    // 一个气泡可能由多条本地行合成（见 [buildChatRows]）。按 partsJson 记忆化：流式期间每 ~50ms
    //（spec §12 的合帧间隔）只有最后一条消息的内容变化，其余历史块直接命中缓存，不重新解析。
    val parts = remember(entities) {
        entities.flatMap { PartsCodec.decode(json, it.partsJson) }
    }
    val isUser = entities.first().role == ROLE_USER
    val streaming = entities.any { it.status == MessageStatus.STREAMING }
    // 失败 / 中断按「组里有没有」判断 —— 这一轮被拆成多条行，状态可能落在其中任意一条上。
    val failed = entities.firstOrNull { it.status == MessageStatus.FAILED }
    val interrupted = entities.any { it.status == MessageStatus.INTERRUPTED }
    // parts → 渲染块：把 reasoning 与工具调用并进同一个「思考过程」（见 [toBlocks]）。
    val blocks = remember(parts, streaming) { parts.toBlocks(streaming) }
    // 这一轮「思考 + 工具」的耗时：见 [thinkingDurationMs]。
    val thinkingDuration = remember(entities) { entities.thinkingDurationMs() }

    // 气泡宽度上限跟着系统字号走（spec §12「大字体」、§19 第 22 条）：默认字号下仍是 340dp，
    // 字号放大后要是不放宽，一条正文会被挤成又窄又高的一列。最终宽度仍受所在行的屏宽约束。
    val bubbleMaxWidth = BUBBLE_MAX_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onMessageTap),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = bubbleMaxWidth),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (parts.isEmpty()) {
                    // 服务端只给了非正文块（或历史还在投影）时，不留一个完全空白的气泡
                    Text(text = "…", style = MaterialTheme.typography.bodyMedium)
                }

                blocks.forEach { block ->
                    when (block) {
                        is MessageBlock.Thinking -> ThinkingBlock(block.parts, thinkingDuration)

                        is MessageBlock.Text -> MessageText(
                            text = block.text,
                            caret = block.isTail,
                            streaming = streaming,
                        )

                        is MessageBlock.Attachment -> AttachmentBubble(
                            part = block.part,
                            threadId = threadId,
                            cachedAttachments = cachedAttachments,
                            onEnsure = onEnsureAttachment,
                        )

                        is MessageBlock.Error -> ToolCard(
                            title = block.part.text.orEmpty(),
                            body = null,
                            bodyLabel = stringResource(R.string.chat_tool_result),
                            isError = true,
                        )
                    }
                }

                if (!streaming && failed != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = failed.errorInfo.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // 半截消息（spec §10、§19 第 11 条）：明确说出「这轮是断的」，并给一个以服务端
                // 为准重取的入口。不自动重取 —— 断流时用户最需要的是先看清眼前这一截。
                if (interrupted) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.chat_interrupted),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.chat_refetch_turn),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onRefetch)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 消息正文，带 spec §12 的两条「长内容保护」：
 *
 * 1. 超过 [LONG_TEXT_CHARS] 的正文**默认折叠** —— 一条两万字正文的全量布局正是滚动掉帧的
 *    主因（§19 第 17 条），折叠后只渲染 [COLLAPSED_TEXT_CHARS] 字。
 * 2. 超长行交给 Markdown 渲染器：代码块自带横向滚动（`MarkdownCode` 内部就是
 *    `horizontalScroll`），表格自己处理溢出 —— 不再需要按行宽做近似判断。
 *
 * **流式中的那条不折叠**：用户此刻盯着看的就是它；而且合帧后长度每 50ms 都在变，
 * 折叠态会在「够长 / 不够长」之间来回跳。
 */
@Composable
private fun MessageText(text: String, caret: Boolean, streaming: Boolean) {
    val collapsible = !streaming && text.length > LONG_TEXT_CHARS
    var expanded by remember { mutableStateOf(false) }
    val shown = if (collapsible && !expanded) text.take(COLLAPSED_TEXT_CHARS) else text
    val body = shown + if (caret) CARET else ""

    MarkdownText(markdown = body, contentColor = LocalContentColor.current)

    if (collapsible) {
        Text(
            text = if (expanded) {
                stringResource(R.string.chat_text_collapse)
            } else {
                stringResource(R.string.chat_text_expand, text.length)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(top = 4.dp),
        )
    }
}

internal fun shouldShowModelSelector(inputFocused: Boolean): Boolean = inputFocused

/**
 * 正文的 Markdown 渲染（spec §7 选型 `multiplatform-markdown-renderer-m3`）。
 *
 * 解析交给 [rememberMarkdownState]：它按内容 `remember` 并在 `Dispatchers.Default` 上异步解析 ——
 * 历史块的内容不变就命中缓存，只有最后那条还在流式的消息会重新解析（spec §12）。
 *
 * 异步解析有个副作用：内容一变，新 state 会先回到 `Loading`。若把 Loading 渲染成空白，
 * 流式期间每 50ms 一次的内容更新都会让气泡塌一下（代码块、表格尤其明显）。所以这里记住
 * 最近一次解析成功的节点，解析没回来时继续渲染它；一次都还没解析出来（首帧）就退回纯文本，
 * 保证任何一帧气泡里都有内容。
 *
 * `modifier` 显式传 `Modifier`：渲染器默认是 `fillMaxSize()`，那会让每条消息的气泡都被撑到宽度上限。
 */
@Composable
private fun MarkdownText(markdown: String, contentColor: Color) {
    val markdownState = rememberMarkdownState(markdown)
    val parsed by markdownState.state.collectAsState()

    var lastParsed by remember { mutableStateOf<State.Success?>(null) }
    val success = parsed as? State.Success
    SideEffect { if (success != null) lastParsed = success }

    val rendered = success ?: lastParsed
    if (rendered == null) {
        Text(text = markdown, style = MaterialTheme.typography.bodyMedium, color = contentColor)
        return
    }

    Markdown(
        state = rendered,
        colors = markdownColor(
            text = contentColor,
            // 气泡底色是 `primaryContainer` / `surfaceVariant`，渲染器默认的 `onBackground`
            // 系列在这里对比度不够，一律从气泡的内容色派生。
            codeBackground = contentColor.copy(alpha = 0.10f),
            inlineCodeBackground = contentColor.copy(alpha = 0.10f),
            dividerColor = contentColor.copy(alpha = 0.24f),
            tableBackground = contentColor.copy(alpha = 0.05f),
        ),
        typography = chatMarkdownTypography(),
        modifier = Modifier,
    )
}

/**
 * 聊天气泡里的 Markdown 排版：正文尺寸对齐气泡原本的 `bodyMedium`，各级标题收敛到
 * `title*` 档 —— 渲染器默认把 h1/h2 映射到 `displayLarge`/`displayMedium`，在气泡里会大得离谱。
 */
@Composable
private fun chatMarkdownTypography(): MarkdownTypography {
    val body = MaterialTheme.typography.bodyMedium
    val mono = body.copy(fontFamily = FontFamily.Monospace)
    return markdownTypography(
        h1 = MaterialTheme.typography.titleLarge,
        h2 = MaterialTheme.typography.titleMedium,
        h3 = MaterialTheme.typography.titleSmall,
        h4 = MaterialTheme.typography.titleSmall,
        h5 = MaterialTheme.typography.labelLarge,
        h6 = MaterialTheme.typography.labelLarge,
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
        quote = body.copy(fontStyle = FontStyle.Italic),
        code = mono,
        inlineCode = mono,
        link = body.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
    )
}

/**
 * 一条消息的渲染块。[MessageBubble] 先把线性的 parts 归并成块再渲染。
 *
 * 归并的目的是让会话流默认只剩「思考过程（收起的一行）+ 回复正文」：`reasoning` 与工具调用 /
 * 结果并进同一个 [Thinking]，不再让每个工具调用各占一张顶层卡片（spec §11）。
 */
private sealed interface MessageBlock {
    data class Thinking(val parts: List<Part>) : MessageBlock
    data class Text(val text: String, val isTail: Boolean) : MessageBlock
    data class Attachment(val part: Part) : MessageBlock
    data class Error(val part: Part) : MessageBlock
}

/**
 * 把线性的 parts 归并成渲染块：整条消息的 `reasoning` / `tool_call` / `tool_result` 全部收敛进
 * 同一个 [MessageBlock.Thinking]（落在首次出现的位置），`text` / 附件 / 错误各自成块。
 */
private fun List<Part>.toBlocks(streaming: Boolean): List<MessageBlock> {
    // 整条消息的思考与工具收敛成**一块**：不管它们原本是否被正文打断，界面上都只出一个「思考过程」。
    val thinking = filter {
        it.type == PartType.REASONING || it.type == PartType.TOOL_CALL || it.type == PartType.TOOL_RESULT
    }

    val out = mutableListOf<MessageBlock>()
    var thinkingEmitted = false
    forEachIndexed { index, part ->
        when (part.type) {
            // 只在首次遇到思考 / 工具时插入这一块，后续同类片段都归进它里面。
            PartType.REASONING, PartType.TOOL_CALL, PartType.TOOL_RESULT ->
                if (!thinkingEmitted) {
                    out += MessageBlock.Thinking(thinking)
                    thinkingEmitted = true
                }

            PartType.TEXT -> out += MessageBlock.Text(part.text.orEmpty(), streaming && index == lastIndex)

            PartType.ATTACHMENT -> out += MessageBlock.Attachment(part)

            PartType.ERROR -> out += MessageBlock.Error(part)

            else -> Unit
        }
    }
    return out
}

/**
 * 「思考过程」展开后的一条内容：一段思考正文，或一次工具调用（参数 + 结果）。
 *
 * 工具调用的参数 / 结果**不一次铺开**（spec §11）：折叠时只显示工具名，点开这一条才看详情。
 */
private sealed interface ThinkingEntry {
    data class Reasoning(val text: String) : ThinkingEntry
    data class Tool(val name: String, val args: String?, val output: String?, val isError: Boolean) : ThinkingEntry
}

/**
 * 把「思考过程」里的 parts 排成条目：`reasoning` 各成一条；`tool_call` 与其结果合成**一条** ——
 * 上游没把调用 id 带到 part 上，只能按「出现顺序 + 同名」配对。
 *
 * 配不到调用的结果单独成条（历史被截断时会出现），信息不丢。
 */
private fun List<Part>.toThinkingEntries(): List<ThinkingEntry> {
    val results = filter { it.type == PartType.TOOL_RESULT }.toMutableList()
    val out = mutableListOf<ThinkingEntry>()
    forEach { part ->
        when (part.type) {
            PartType.REASONING -> part.text?.takeIf(String::isNotBlank)
                ?.let { out += ThinkingEntry.Reasoning(it) }

            PartType.TOOL_CALL -> {
                val name = part.name.orEmpty()
                val at = results.indexOfFirst { it.name.orEmpty() == name }
                val result = if (at >= 0) results.removeAt(at) else null
                out += ThinkingEntry.Tool(name, part.args, result?.output, result?.errorCode != null)
            }

            else -> Unit
        }
    }
    results.forEach { out += ThinkingEntry.Tool(it.name.orEmpty(), null, it.output, it.errorCode != null) }
    return out
}

/**
 * 这一轮「思考 + 工具」的耗时。
 *
 * 服务端把一轮回复拆成多条消息下发，每条都带毫秒级的 `timestamp`，落到本地的 `sortOrder` 与
 * 实时行（`System.currentTimeMillis()`）同一量纲（见 `ChatRepository.toEntity`）。所以：
 *
 * - **多条**：首末两行的时间差就是从开始思考到正文落定的时长；
 * - **单条**：只有刚刚流完、还没被历史替换掉的那一轮会这样（本地实时行），拿不到分阶段时间戳，
 *   改用「行建立时刻 → 最后一次落库」的差（`updatedAt`）。历史行的 `updatedAt` 等于行自身时间，
 *   差为 0，自然落到 `null`，不会被误算成一条跨越几天的耗时。
 *
 * 不足 [MIN_THINKING_DURATION_MS] 不值得显示，返回 null。
 */
private fun List<MessageEntity>.thinkingDurationMs(): Long? {
    val span = if (size >= 2) {
        last().sortOrder - first().sortOrder
    } else {
        first().updatedAt - first().sortOrder
    }
    return span.takeIf { it >= MIN_THINKING_DURATION_MS }
}

/** 折叠着的思考过程里显示的耗时（`45s` / `1m 20s`；秒级，四舍五入）。 */
@Composable
private fun thinkingDurationLabel(ms: Long): String {
    val total = (ms + 500) / 1000
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes <= 0) {
        stringResource(R.string.chat_duration_seconds, seconds)
    } else {
        stringResource(R.string.chat_duration_minutes, minutes, seconds)
    }
}

/**
 * 「思考过程」折叠区：把一轮里的 `reasoning` 与工具调用 / 结果**并成一块**（spec §11），
 * 默认收起、点标题行展开。
 *
 * 之所以合并：会话流里用户想一眼看到的只有回复正文，模型在想什么属于过程细节 —— 让每个思考 /
 * 工具片段各占一块会把正文挤散。收起时标题给出**整轮思考耗时**（拿不到时只留「思考过程」）；
 * 展开后是思考正文 + 各次工具调用（每次只占一行工具名，各自再点开才看参数 / 结果）。
 */
@Composable
private fun ThinkingBlock(parts: List<Part>, durationMs: Long?) {
    var expanded by remember { mutableStateOf(false) }
    val entries = remember(parts) { parts.toThinkingEntries() }
    val title = if (durationMs == null) {
        stringResource(R.string.chat_reasoning)
    } else {
        stringResource(R.string.chat_reasoning) + " · " + thinkingDurationLabel(durationMs)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }

        // 正文只在展开时渲染（不预先摆一份）：`reasoning` 与 `tool_call` 的 `args` 都是**流式片段**，
        // 预渲染会让块高随每帧变化（spec §12）。
        if (expanded) {
            entries.forEach { entry ->
                when (entry) {
                    is ThinkingEntry.Reasoning -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is ThinkingEntry.Tool -> ToolEntry(
                        name = entry.name,
                        args = entry.args,
                        output = entry.output,
                        isError = entry.isError,
                    )
                }
            }
        }
    }
}

/**
 * 「思考过程」里的一次工具调用：默认只占一行（工具名），点开才看参数 / 结果 ——
 * 折叠时不再把整段代码记录铺开（spec §11）。
 */
@Composable
private fun ToolEntry(
    name: String,
    args: String?,
    output: String?,
    isError: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val hasArgs = !args.isNullOrBlank()
    val hasOutput = !output.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = hasArgs || hasOutput || isError) { expanded = !expanded }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = name.ifBlank { stringResource(R.string.chat_tool_call) },
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasArgs || hasOutput || isError) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (isError) {
            Text(
                text = stringResource(R.string.chat_tool_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (expanded) {
            if (hasArgs) {
                Text(
                    text = stringResource(R.string.chat_tool_args),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = args,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                )
            }
            if (hasOutput) {
                Text(
                    text = stringResource(R.string.chat_tool_result),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                )
            }
        }
    }
}

/**
 * 消息级错误的折叠卡（`PartType.ERROR`）。
 *
 * 工具调用 / 结果已经并进 [ThinkingBlock]，不再走这里。错误仍单独成块 —— 它不是「过程细节」，
 * 而是一条需要在会话流里被看见的结果，所以留在顶层：收起时一行、点开看详情。
 */
@Composable
private fun ToolCard(
    title: String,
    body: String?,
    bodyLabel: String,
    isError: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val hasBody = !body.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = hasBody) { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasBody) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (hasBody && expanded) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = bodyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = body.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
        }

        if (isError) {
            Text(
                text = stringResource(R.string.chat_tool_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun connectionColor(state: ChatSocket.State) = when (state) {
    is ChatSocket.State.Open -> MaterialTheme.colorScheme.primary
    is ChatSocket.State.Connecting -> MaterialTheme.colorScheme.tertiary
    is ChatSocket.State.Closed -> MaterialTheme.colorScheme.error
    ChatSocket.State.Idle -> MaterialTheme.colorScheme.outline
}

/**
 * 上下文压缩分隔线（docs/spec.md §8.2）。
 *
 * 只说明「这里发生过压缩」，不展开服务端给的摘要：摘要是一段完整叙述，摊在消息流中间会把
 * 对话本身挤散，而它真正的用处是提示用户更早的内容已经不是原文了。
 */
@Composable
private fun CompactDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.chat_compacted),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

internal fun shouldShowWorkbenchBar(
    imeVisible: Boolean,
    inputFocused: Boolean,
): Boolean = !imeVisible && !inputFocused

internal fun shouldApplyChatBottomBarNavigationPadding(imeVisible: Boolean): Boolean = !imeVisible

internal fun shouldShowChatBackAction(
    messages: List<MessageEntity>,
    thread: ThreadEntity?,
): Boolean = messages.isNotEmpty() || !thread?.title.isNullOrBlank()

/**
 * 消息流里的一行。
 *
 * 压缩标记与消息混在同一条序列里，LazyColumn 才能统一编号 —— 「回到最新」要滚到的绝对
 * 下标就依赖这个编号（spec §12）。
 */
private sealed interface ChatRow {
    val key: String

    /**
     * 一个气泡。[entities] 是该气泡包含的本地消息行 —— 用户消息恒为单条；服务端把一轮回复拆成
     * 多条下发（`assistant` 的思考 / 工具调用、`tool` 的工具结果、`assistant` 的正文各占一条），
     * 这些连续的非用户行在界面上属于同一个气泡，合并后一轮只出一个「思考过程」折叠区（spec §11）。
     */
    data class Message(val entities: List<MessageEntity>) : ChatRow {
        override val key: String get() = entities.first().id
    }

    /** 同一个毫秒不会被压缩两次，所以时间戳本身足够当稳定 key。 */
    data class Compact(val createdAt: Long) : ChatRow {
        override val key: String get() = "compact-$createdAt"
    }
}

/**
 * 把压缩标记插进消息流（docs/spec.md §8.2）。
 *
 * 标记只带时间戳，而消息的 `sortOrder` 与它同一量纲（历史消息是 `created_at * 1000`，
 * 本地实时行是毫秒时间戳），所以「第一条晚于标记的消息」就是它该出现的位置。
 * 比所有消息都新的标记排在队尾：压缩本来就发生在回合之间，那时确实还没有下一条消息。
 *
 * [markers] 必须按时间正序（`CompactMarkerDao.observeByThread` 已经保证了）。
 *
 * 连续的非用户消息归并成同一个 [ChatRow.Message]：服务端把一轮回复拆成多条行下发，它们共用
 * 一个气泡，合并后一轮只出一个「思考过程」折叠区，不会在会话流里出现连续的多段思考过程（spec §11）。
 * 用户消息与压缩标记都会打断分组。
 */
private fun buildChatRows(
    messages: List<MessageEntity>,
    markers: List<CompactMarkerEntity>,
): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + markers.size)
    var group = ArrayList<MessageEntity>()

    fun flush() {
        if (group.isNotEmpty()) {
            rows += ChatRow.Message(group)
            group = ArrayList()
        }
    }

    var next = 0
    messages.forEach { message ->
        while (next < markers.size && markers[next].createdAt <= message.sortOrder) {
            flush()
            rows += ChatRow.Compact(markers[next++].createdAt)
        }
        if (message.role == ROLE_USER) {
            flush()
            rows += ChatRow.Message(listOf(message))
        } else {
            group += message
        }
    }
    flush()
    while (next < markers.size) rows += ChatRow.Compact(markers[next++].createdAt)
    return rows
}

private const val ROLE_USER = "user"
private const val CARET = "▍"

/**
 * 拍照要写的临时文件（spec §14）。authority 必须与 `AndroidManifest.xml` 里那个
 * FileProvider 的 `android:authorities`（`${applicationId}.fileprovider`）一致，
 * 路径也要落在 `res/xml/file_paths.xml` 开出来的 `camera/` 里，否则会抛 IllegalArgumentException。
 */
private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(dir, "shot-${System.currentTimeMillis()}.jpg"),
    )
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 短于这个耗时不值得在「思考过程」标题上显示（spec §11）。 */
private const val MIN_THINKING_DURATION_MS = 1_000L

/** 正文超过这个字符数就默认折叠（spec §12「长内容保护」、§19 第 17 条）。 */
private const val LONG_TEXT_CHARS = 20_000

/** 折叠时先露出的字符数：够看清开头，又不至于让文本布局拖慢滚动。 */
private const val COLLAPSED_TEXT_CHARS = 2_000

/** 气泡宽度上限（spec §12「大字体」：这个上限随系统字号放宽，见 `MessageBubble`）。 */
private val BUBBLE_MAX_WIDTH = 340.dp

/** 底部死区：用户落在离底 32dp 以内才继续自动跟随（spec §12）。 */
private val SCROLL_DEAD_ZONE = 32.dp

/** 同步中的顶部细进度条高度（spec §12「顶部 1px 不定进度条」）。 */
private val TOP_PROGRESS_HEIGHT = 2.dp

/** 骨架屏气泡条数：够铺满一屏即可，不做无限占位。 */
private const val SKELETON_BUBBLES = 4
