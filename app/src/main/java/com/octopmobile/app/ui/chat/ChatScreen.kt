package com.octopmobile.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MessageEntity
import com.octopmobile.app.data.db.MessageStatus
import com.octopmobile.app.data.model.HitlPending
import com.octopmobile.app.data.model.PartType
import com.octopmobile.app.data.model.PartsCodec
import com.octopmobile.app.data.model.UploadedAttachment
import com.octopmobile.app.data.remote.dto.HitlDecision
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.ui.asText
import kotlinx.serialization.json.Json

/**
 * 单会话页（docs/spec.md §18 M1/M2）。
 *
 * M1 做「看」：历史分页 + `token` / `reasoning` 流式渲染 + 连接状态。
 * M2 做「写」：输入栏发 `user_turn`、回合中置灰并可 `cancel`、附件上传、工具调用折叠卡、
 * HITL 确认卡（挂起期间输入栏同样禁用 —— 服务端会以 `hitl_paused` 拒绝新回合）。
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

    // 挂起期间服务端会拒绝新回合（`SteerRejectReason.HITL_PAUSED`），所以和「回合中」一样禁用输入。
    val blocked = turnActive || pending != null

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(context, uri)
    }

    val listState = rememberLazyListState()

    // 滚动跟随：只有用户没主动上滑时才贴住底部（spec §12 的死区判定在 M3 细化）
    val followBottom = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) followBottom.value = !canScrollForward
            }
    }

    val stickKey = messages.size to (messages.lastOrNull()?.partsJson?.length ?: 0)
    LaunchedEffect(stickKey) {
        if (followBottom.value && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
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
                onAttach = { filePicker.launch(arrayOf("*/*")) },
                onRemove = viewModel::removeAttachment,
                onResolve = viewModel::resolveHitl,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                messages.isEmpty() && state.historyLoading -> CenteredNotice(
                    text = stringResource(R.string.chat_history_loading),
                    busy = true,
                )

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
                    items(messages, key = { it.id }) { entity ->
                        MessageBubble(entity = entity, json = container.json)
                    }
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
 */
@Composable
private fun ChatBottomBar(
    blocked: Boolean,
    pending: HitlPending?,
    attachments: List<UploadedAttachment>,
    busy: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onAttach: () -> Unit,
    onRemove: (UploadedAttachment) -> Unit,
    onResolve: (String, String?) -> Unit,
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
                onAttach = onAttach,
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
    onAttach: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val sendable = !blocked && (input.isNotBlank() || canSend)

    Row(verticalAlignment = Alignment.Bottom) {
        IconButton(onClick = onAttach, enabled = !blocked) {
            Icon(
                imageVector = Icons.Filled.AttachFile,
                contentDescription = stringResource(R.string.chat_attach),
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            enabled = !blocked,
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            // 自增高到 6 行，再多就内部滚动（spec §12：输入区不做全屏展开）。
            maxLines = 6,
            shape = RoundedCornerShape(20.dp),
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
            IconButton(
                onClick = {
                    val text = input
                    input = ""
                    onSend(text)
                },
                enabled = sendable,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
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

@Composable
private fun CenteredNotice(text: String, busy: Boolean = false, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
        }
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
private fun MessageBubble(entity: MessageEntity, json: Json) {
    // 按 partsJson 记忆化：流式期间每 120ms 只有最后一条消息的内容变化，其余直接命中缓存
    val parts = remember(entity.partsJson) { PartsCodec.decode(json, entity.partsJson) }
    val isUser = entity.role == ROLE_USER
    val streaming = entity.status == MessageStatus.STREAMING

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
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (parts.isEmpty()) {
                    // 服务端只给了非正文块（或历史还在投影）时，不留一个完全空白的气泡
                    Text(text = "…", style = MaterialTheme.typography.bodyMedium)
                }

                parts.forEachIndexed { index, part ->
                    val isTail = streaming && index == parts.lastIndex
                    when (part.type) {
                        PartType.TEXT -> Text(
                            text = (part.text.orEmpty()) + if (isTail) CARET else "",
                            style = MaterialTheme.typography.bodyMedium,
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

                        PartType.ATTACHMENT -> AttachmentChip(
                            filename = part.filename.orEmpty().ifBlank { DEFAULT_ATTACHMENT_NAME },
                            onRemove = null,
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
            }
        }
    }
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

private const val ROLE_USER = "user"
private const val CARET = "▍"

/** 短于这个长度的工具结果默认展开，长输出折叠成一行预览。 */
private const val TOOL_PREVIEW_CHARS = 160
private const val DEFAULT_ATTACHMENT_NAME = "attachment"
