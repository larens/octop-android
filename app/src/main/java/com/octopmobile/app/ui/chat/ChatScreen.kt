package com.octopmobile.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.data.attachments.AttachmentRefs
import com.octopmobile.app.data.db.AttachmentEntity
import com.octopmobile.app.data.db.CompactMarkerEntity
import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.MessageStatus
import com.octopmobile.app.data.model.HitlPending
import com.octopmobile.app.data.model.Part
import com.octopmobile.app.data.model.PartType
import com.octopmobile.app.data.model.PartsCodec
import com.octopmobile.app.data.model.UploadedAttachment
import com.octopmobile.app.data.remote.dto.HitlDecision
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.service.TurnService
import com.octopmobile.app.ui.AppActions
import com.octopmobile.app.ui.PendingChatStart
import com.octopmobile.app.ui.asText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    agentId: String,
    threadId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(agentId, threadId))

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

    // 语音输入与语音快捷方式都落回同一个输入框；焦点用于识别不可用时的输入法兜底。
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

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

    // 语音输入（spec §14）：用系统识别服务把说的话转成文字填进输入框，用户过目后再发。
    // 输入框文本提在这里持有，就是为了让识别结果和手打文字落在同一个地方。
    // 识别器一直挂在组合里（不是按下才建），省掉每次点击的初始化延迟。
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    // 开口前已有的文字。识别过程中返回的是「整句话的重写」，所以在它后面整体覆盖，
    // 而不是把每个中间结果往输入框尾巴上追加 —— 那会拼出一串重复的前缀。
    var voiceBase by remember { mutableStateOf("") }

    val recognizer = remember {
        runCatching {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                null
            }
        }.getOrNull()
    }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }

            override fun onError(error: Int) {
                listening = false
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT 只是「没听清」，不是故障，不打扰用户。
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    Toast.makeText(context, R.string.err_voice_failed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) input = withVoicePrefix(voiceBase, text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) input = withVoicePrefix(voiceBase, text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }

    fun startVoice() {
        if (recognizer == null) {
            // 设备上没有识别服务（少见）：退回「聚焦输入框 + 唤起输入法」，键盘上也带语音键。
            runCatching { inputFocus.requestFocus() }
            keyboard?.show()
            return
        }
        if (listening) {
            recognizer.stopListening()
            return
        }
        voiceBase = input
        val started = runCatching { recognizer.startListening(speechIntent) }.isSuccess
        if (started) {
            listening = true
        } else {
            Toast.makeText(context, R.string.err_voice_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // 录音的是识别服务，但要拿 RECORD_AUDIO —— 没授权时 startListening 只会回
    // ERROR_INSUFFICIENT_PERMISSIONS，所以先要权限再开口。
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startVoice() }

    fun requestVoice() {
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            startVoice()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 回合开始或被 HITL 挂起时输入区会禁用，识别也该停下来，免得录进来的话没人接。
    LaunchedEffect(blocked) {
        if (blocked && listening) recognizer?.stopListening()
    }

    // 快捷方式 / 分享的最后一跳：会话已由列表页建好，这里把动作补上（spec §14）。
    // `ready` 是门槛：ViewModel 的仓库是异步就绪的，早于它执行会被 `share` 静默丢掉。
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    LaunchedEffect(chatStart, ready) {
        if (!ready) return@LaunchedEffect
        val start = PendingChatStart.take() ?: return@LaunchedEffect
        when (start.action) {
            AppActions.SHARE -> viewModel.share(context, start.text, start.uris)

            AppActions.VOICE -> requestVoice()

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

    // 回到前台立刻重连一次（spec §10）：退避等待对「刚恢复网络」这种情况没有意义，
    // 而用户从后台切回来正是最可能刚刚恢复网络的时候。
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
        }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = thread?.title?.takeIf { it.isNotBlank() } ?: threadId,
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
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
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
            ChatBottomBar(
                blocked = blocked,
                pending = pending,
                attachments = attachments,
                busy = busy,
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
                onVoiceInput = { requestVoice() },
                input = input,
                onInputChange = { input = it },
                listening = listening,
                onRemove = viewModel::removeAttachment,
                onResolve = viewModel::resolveHitl,
                inputModifier = Modifier.focusRequester(inputFocus),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                                entity = row.entity,
                                json = container.json,
                                threadId = threadId,
                                cachedAttachments = cachedAttachments,
                                onEnsureAttachment = viewModel::ensureAttachment,
                                // 「重新拉取该轮」= 以服务端为准重取最新一页：本地那半截行会在
                                // 历史占位清理里退场（见 ChatRepository.loadHistory）。
                                onRefetch = viewModel::retry,
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

// ------------------------------------------------------------------ 输入区

/**
 * 底部输入栏。
 *
 * [blocked]（回合进行中或 HITL 挂起）时输入框与附件按钮置灰、发送键换成中止
 * —— M2 不做 steer，等 `turn.ended` 后才允许再发（docs/spec.md §18.1）。
 *
 * [inputModifier] 单独留给输入框：语音输入快捷方式要靠它把焦点送进去（spec §14）。
 */
@Composable
private fun ChatBottomBar(
    blocked: Boolean,
    pending: HitlPending?,
    attachments: List<UploadedAttachment>,
    busy: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVoiceNote: () -> Unit,
    onVoiceInput: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    listening: Boolean,
    onRemove: (UploadedAttachment) -> Unit,
    onResolve: (String, String?) -> Unit,
    inputModifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (pending != null) {
                HitlCard(pending = pending, onResolve = onResolve)
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
                onSend = onSend,
                onCancel = onCancel,
                onUploadFile = onUploadFile,
                onUploadImage = onUploadImage,
                onTakePhoto = onTakePhoto,
                onRecordVoiceNote = onRecordVoiceNote,
                onVoiceInput = onVoiceInput,
                input = input,
                onInputChange = onInputChange,
                listening = listening,
                modifier = inputModifier,
            )
        }
    }
}

@Composable
private fun InputRow(
    blocked: Boolean,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVoiceNote: () -> Unit,
    onVoiceInput: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val sendable = !blocked && (input.isNotBlank() || canSend)
    var menuExpanded by remember { mutableStateOf(false) }

    fun submit() {
        if (!sendable) return
        val text = input
        onInputChange("")
        onSend(text)
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                enabled = !blocked,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chat_more_actions),
                )
            }
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
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .then(modifier),
            enabled = !blocked,
            placeholder = {
                Text(
                    stringResource(
                        if (listening) R.string.chat_voice_listening else R.string.chat_input_hint,
                    ),
                )
            },
            // 自增高到 6 行，再多就内部滚动（spec §12：输入区不做全屏展开）。
            maxLines = 6,
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )

        if (blocked) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.chat_stop),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            IconButton(onClick = onVoiceInput) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = stringResource(
                        if (listening) R.string.chat_voice_listening else R.string.chat_voice_input,
                    ),
                    tint = if (listening) {
                        MaterialTheme.colorScheme.error
                    } else if (sendable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
                )
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
    entity: MessageEntity,
    json: Json,
    threadId: String,
    cachedAttachments: Map<String, AttachmentEntity>,
    onEnsureAttachment: suspend (String?, String?) -> Unit,
    onRefetch: () -> Unit,
) {
    // 按 partsJson 记忆化：流式期间每 ~50ms（spec §12 的合帧间隔）只有最后一条消息的内容变化，
    // 其余历史块直接命中缓存，不重新解析。
    val parts = remember(entity.partsJson) { PartsCodec.decode(json, entity.partsJson) }
    val isUser = entity.role == ROLE_USER
    val streaming = entity.status == MessageStatus.STREAMING

    // 气泡宽度上限跟着系统字号走（spec §12「大字体」、§19 第 22 条）：默认字号下仍是 340dp，
    // 字号放大后要是不放宽，一条正文会被挤成又窄又高的一列。最终宽度仍受所在行的屏宽约束。
    val bubbleMaxWidth = BUBBLE_MAX_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
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

                parts.forEachIndexed { index, part ->
                    val isTail = streaming && index == parts.lastIndex
                    when (part.type) {
                        PartType.TEXT -> MessageText(
                            text = part.text.orEmpty(),
                            caret = isTail,
                            streaming = streaming,
                        )

                        PartType.REASONING -> ReasoningBlock(part.text.orEmpty())

                        PartType.TOOL_CALL -> ToolCard(
                            title = part.name.orEmpty().ifBlank { stringResource(R.string.chat_tool_args) },
                            body = part.args,
                            bodyLabel = stringResource(R.string.chat_tool_args),
                            isError = false,
                            initiallyExpanded = false,
                        )

                        PartType.TOOL_RESULT -> ToolCard(
                            title = part.name.orEmpty().ifBlank { stringResource(R.string.chat_tool_result) },
                            body = part.output,
                            bodyLabel = stringResource(R.string.chat_tool_result),
                            isError = part.errorCode != null,
                            initiallyExpanded = (part.output?.length ?: 0) <= TOOL_PREVIEW_CHARS,
                        )

                        PartType.ATTACHMENT -> AttachmentBubble(
                            part = part,
                            threadId = threadId,
                            cachedAttachments = cachedAttachments,
                            onEnsure = onEnsureAttachment,
                        )

                        PartType.ERROR -> ToolCard(
                            title = part.text.orEmpty(),
                            body = null,
                            bodyLabel = stringResource(R.string.chat_tool_result),
                            isError = true,
                            initiallyExpanded = false,
                        )

                        else -> Unit
                    }
                }

                if (!streaming && entity.status == MessageStatus.FAILED) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entity.errorInfo.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // 半截消息（spec §10、§19 第 11 条）：明确说出「这轮是断的」，并给一个以服务端
                // 为准重取的入口。不自动重取 —— 断流时用户最需要的是先看清眼前这一截。
                if (entity.status == MessageStatus.INTERRUPTED) {
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
 * 1. 超过 [LONG_TEXT_CHARS] 的正文**默认折叠** —— 一条两万字正文的全量文本布局正是滚动掉帧的
 *    主因（§19 第 17 条），折叠后只布局 [COLLAPSED_TEXT_CHARS] 字。
 * 2. 含超长单行的正文**不折行、改横向滚动** —— 纯文本渲染下没有代码块/表格的语法信息，
 *    按行宽近似判断（见 [hasVeryLongLine]），横向滚动是这类内容唯一读得懂的形态。
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

    if (hasVeryLongLine(body)) {
        // 不折行时由 Box 承担横向滚动；气泡宽度仍由外层的 `widthIn` 约束。
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                softWrap = false,
            )
        }
    } else {
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }

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

/**
 * 有没有「长到不该折行」的一行。
 *
 * 纯文本渲染拿不到代码块/表格的语法信息，只能按行宽近似：一行超过 [LONG_LINE_CHARS] 字符，
 * 自动折行后基本读不成样子（代码、表格、长 URL 都属于这类）。
 */
private fun hasVeryLongLine(text: String): Boolean {
    var start = 0
    while (start < text.length) {
        val newline = text.indexOf('\n', start)
        val end = if (newline < 0) text.length else newline
        if (end - start > LONG_LINE_CHARS) return true
        if (newline < 0) return false
        start = newline + 1
    }
    return false
}

/** 思考过程默认只露三行，点一下展开（spec §11「`reasoning` 默认折叠」）。 */
@Composable
private fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_reasoning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 工具调用 / 结果的折叠卡（M2 把 M1 的单行升级成卡片）。
 *
 * `tool_call_chunk` 的 `name` 与 `args` 都是**流式片段**（`stream_project.py:141-160`），
 * 所以折叠状态下只露第一行，避免流式期间整块跳动。
 */
@Composable
private fun ToolCard(
    title: String,
    body: String?,
    bodyLabel: String,
    isError: Boolean,
    initiallyExpanded: Boolean,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
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

        if (hasBody) {
            Spacer(Modifier.height(3.dp))
            if (expanded) {
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
            } else {
                Text(
                    text = body.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

/**
 * 消息流里的一行。
 *
 * 压缩标记与消息混在同一条序列里，LazyColumn 才能统一编号 —— 「回到最新」要滚到的绝对
 * 下标就依赖这个编号（spec §12）。
 */
private sealed interface ChatRow {
    val key: String

    data class Message(val entity: MessageEntity) : ChatRow {
        override val key: String get() = entity.id
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
 */
private fun buildChatRows(
    messages: List<MessageEntity>,
    markers: List<CompactMarkerEntity>,
): List<ChatRow> {
    if (markers.isEmpty()) return messages.map { ChatRow.Message(it) }

    val rows = ArrayList<ChatRow>(messages.size + markers.size)
    var next = 0
    messages.forEach { message ->
        while (next < markers.size && markers[next].createdAt <= message.sortOrder) {
            rows += ChatRow.Compact(markers[next++].createdAt)
        }
        rows += ChatRow.Message(message)
    }
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

/**
 * 把识别结果接到开口前已有的文字后面（spec §14）。识别服务每次给的都是「这一句话的完整
 * 重写」，所以整体覆盖而不是追加；已有的文字按句读留一个空格，别粘在一起。
 */
private fun withVoicePrefix(base: String, text: String): String =
    if (base.isBlank()) text else "${base.trim()} $text"

/** 短于这个长度的工具结果默认展开，长输出折叠成一行预览。 */
private const val TOOL_PREVIEW_CHARS = 160

/** 正文超过这个字符数就默认折叠（spec §12「长内容保护」、§19 第 17 条）。 */
private const val LONG_TEXT_CHARS = 20_000

/** 折叠时先露出的字符数：够看清开头，又不至于让文本布局拖慢滚动。 */
private const val COLLAPSED_TEXT_CHARS = 2_000

/** 单行超过这个字符数就不折行、改横向滚动（`hasVeryLongLine` 的判据）。 */
private const val LONG_LINE_CHARS = 200

/** 气泡宽度上限（spec §12「大字体」：这个上限随系统字号放宽，见 `MessageBubble`）。 */
private val BUBBLE_MAX_WIDTH = 340.dp

/** 底部死区：用户落在离底 32dp 以内才继续自动跟随（spec §12）。 */
private val SCROLL_DEAD_ZONE = 32.dp

/** 同步中的顶部细进度条高度（spec §12「顶部 1px 不定进度条」）。 */
private val TOP_PROGRESS_HEIGHT = 2.dp

/** 骨架屏气泡条数：够铺满一屏即可，不做无限占位。 */
private const val SKELETON_BUBBLES = 4
