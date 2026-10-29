package com.octopmobile.app.ui.workbench

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.db.AutomationTaskStatus
import com.octopmobile.app.data.remote.dto.ConnectorInstanceDto
import com.octopmobile.app.data.repo.ScheduleRule
import com.octopmobile.app.data.repo.ScheduleRules
import com.octopmobile.app.ui.asText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TasksTab { ADDED, RECOMMENDED }

private enum class RecommendationKind { NEWS, MAIL, READING, WEATHER }

private data class TaskRecommendation(
    val id: String,
    val kind: RecommendationKind,
    val titleRes: Int,
    val descriptionRes: Int,
    val naturalRequestRes: Int,
    val connectorKinds: Set<String>,
    val icon: ImageVector,
)

private val TASK_RECOMMENDATIONS = listOf(
    TaskRecommendation(
        "news-morning",
        RecommendationKind.NEWS,
        R.string.tasks_rec_news_title,
        R.string.tasks_rec_news_body,
        R.string.tasks_rec_news_natural,
        setOf("tencent-news"),
        Icons.Filled.Newspaper,
    ),
    TaskRecommendation(
        "industry-signal-afternoon",
        RecommendationKind.NEWS,
        R.string.tasks_rec_signal_title,
        R.string.tasks_rec_signal_body,
        R.string.tasks_rec_signal_natural,
        setOf("tencent-news"),
        Icons.Filled.AutoAwesome,
    ),
    TaskRecommendation(
        "mail-morning",
        RecommendationKind.MAIL,
        R.string.tasks_rec_mail_title,
        R.string.tasks_rec_mail_body,
        R.string.tasks_rec_mail_natural,
        setOf("qq-mail", "agently-cli"),
        Icons.Filled.Email,
    ),
    TaskRecommendation(
        "reading-daily",
        RecommendationKind.READING,
        R.string.tasks_rec_reading_title,
        R.string.tasks_rec_reading_body,
        R.string.tasks_rec_reading_natural,
        setOf("wechat-reading"),
        Icons.Filled.AutoStories,
    ),
    TaskRecommendation(
        "calendar-morning",
        RecommendationKind.WEATHER,
        R.string.tasks_rec_calendar_title,
        R.string.tasks_rec_calendar_body,
        R.string.tasks_rec_calendar_natural,
        setOf("feishu-cli"),
        Icons.Filled.WbSunny,
    ),
    TaskRecommendation(
        "news-weekly",
        RecommendationKind.NEWS,
        R.string.tasks_rec_news_weekly_title,
        R.string.tasks_rec_news_weekly_body,
        R.string.tasks_rec_news_weekly_natural,
        setOf("tencent-news"),
        Icons.Filled.Newspaper,
    ),
    TaskRecommendation(
        "mail-evening",
        RecommendationKind.MAIL,
        R.string.tasks_rec_mail_evening_title,
        R.string.tasks_rec_mail_evening_body,
        R.string.tasks_rec_mail_evening_natural,
        setOf("qq-mail", "agently-cli"),
        Icons.Filled.Email,
    ),
    TaskRecommendation(
        "reading-weekly",
        RecommendationKind.READING,
        R.string.tasks_rec_reading_weekly_title,
        R.string.tasks_rec_reading_weekly_body,
        R.string.tasks_rec_reading_weekly_natural,
        setOf("wechat-reading"),
        Icons.Filled.AutoStories,
    ),
    TaskRecommendation(
        "calendar-preview",
        RecommendationKind.WEATHER,
        R.string.tasks_rec_calendar_preview_title,
        R.string.tasks_rec_calendar_preview_body,
        R.string.tasks_rec_calendar_preview_natural,
        setOf("feishu-cli"),
        Icons.Filled.WbSunny,
    ),
)

/** 服务端自动任务：已添加任务与按已安装连接器生成的推荐分开呈现。 */
@Composable
fun TasksPane(
    onNeedsLogin: () -> Unit,
    onComposerChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory)
    val state = viewModel.uiState
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TasksTab.ADDED) }
    var naturalTaskOpen by remember { mutableStateOf(false) }
    var naturalTaskInitial by remember { mutableStateOf("") }
    var naturalTaskMcpServers by remember { mutableStateOf(emptyList<String>()) }
    var naturalTaskTarget by remember { mutableStateOf<AutomationTaskEntity?>(null) }

    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) onNeedsLogin()
    }
    val notice = state.notice?.asText()
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        viewModel.consumeNotice()
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(state.naturalTaskCompleted) {
        if (!state.naturalTaskCompleted) return@LaunchedEffect
        val edited = naturalTaskTarget != null
        naturalTaskOpen = false
        naturalTaskTarget = null
        Toast.makeText(
            context,
            if (edited) R.string.tasks_natural_updated else R.string.tasks_natural_created,
            Toast.LENGTH_SHORT,
        ).show()
        viewModel.consumeNotice()
    }

    DisposableEffect(naturalTaskOpen) {
        onComposerChanged(naturalTaskOpen)
        onDispose { if (naturalTaskOpen) onComposerChanged(false) }
    }
    BackHandler(enabled = naturalTaskOpen && !state.processingNaturalTask) {
        naturalTaskOpen = false
        naturalTaskTarget = null
        viewModel.dismissNaturalTask()
    }

    if (naturalTaskOpen) {
        NaturalTaskPage(
            initialDescription = naturalTaskInitial,
            target = naturalTaskTarget,
            processing = state.processingNaturalTask,
            error = state.naturalTaskError,
            onDismiss = {
                if (!state.processingNaturalTask) {
                    naturalTaskOpen = false
                    naturalTaskTarget = null
                    viewModel.dismissNaturalTask()
                }
            },
            onSubmit = { description ->
                naturalTaskTarget?.let { viewModel.modifyFromNaturalLanguage(it, description) }
                    ?: viewModel.createFromNaturalLanguage(description, naturalTaskMcpServers)
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        TasksHeader(
            taskCount = tasks.size,
            refreshing = state.syncing,
            onRefresh = viewModel::sync,
            onAdd = {
                naturalTaskInitial = ""
                naturalTaskMcpServers = emptyList()
                naturalTaskTarget = null
                naturalTaskOpen = true
            },
        )
        TasksTabs(selected = tab, onSelect = { tab = it })

        when (tab) {
            TasksTab.ADDED -> AddedTasks(
                tasks = tasks,
                state = state,
                onOpen = { task ->
                    naturalTaskInitial = ""
                    naturalTaskMcpServers = emptyList()
                    naturalTaskTarget = task
                    naturalTaskOpen = true
                },
            )
            TasksTab.RECOMMENDED -> RecommendedTasks(
                tasks = tasks,
                connectors = state.connectors,
                connectorsLoaded = state.connectorsLoaded,
                onAdd = { recommendation, connectors ->
                    naturalTaskInitial = context.getString(recommendation.naturalRequestRes)
                    naturalTaskMcpServers = connectors.map(ConnectorInstanceDto::mcpServerName)
                    naturalTaskTarget = null
                    naturalTaskOpen = true
                },
            )
        }
    }

}

@Composable
private fun TasksHeader(
    taskCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.tasks_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.tasks_header_summary, taskCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (refreshing) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            ) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.tasks_refresh))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, stringResource(R.string.tasks_new))
            }
        }
    }
}

@Composable
private fun TasksTabs(selected: TasksTab, onSelect: (TasksTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TasksTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(tab) },
                shape = RoundedCornerShape(18.dp),
                color = if (active) MaterialTheme.colorScheme.surface else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
                shadowElevation = if (active) 2.dp else 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (tab == TasksTab.ADDED) Icons.Filled.TaskAlt else Icons.Filled.EventAvailable,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        stringResource(
                            if (tab == TasksTab.ADDED) R.string.tasks_tab_added else R.string.tasks_tab_recommended,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddedTasks(
    tasks: List<AutomationTaskEntity>,
    state: TasksUiState,
    onOpen: (AutomationTaskEntity) -> Unit,
) {
    when {
        !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        tasks.isEmpty() -> Column(Modifier.padding(16.dp)) {
            EmptyCard(
                title = stringResource(R.string.tasks_empty_title),
                body = if (state.agentId == null) stringResource(R.string.tasks_agent_missing) else {
                    state.error?.asText() ?: stringResource(R.string.tasks_empty_body)
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
                    onClick = { onOpen(task) },
                )
            }
        }
    }
}

@Composable
private fun RecommendedTasks(
    tasks: List<AutomationTaskEntity>,
    connectors: List<ConnectorInstanceDto>,
    connectorsLoaded: Boolean,
    onAdd: (TaskRecommendation, List<ConnectorInstanceDto>) -> Unit,
) {
    var batch by rememberSaveable { mutableStateOf(0) }
    val available = TASK_RECOMMENDATIONS.mapNotNull { recommendation ->
        val matches = connectors.filter { it.kind in recommendation.connectorKinds }
        recommendation.takeIf { matches.isNotEmpty() }?.let { it to matches }
    }
    val visible = if (available.isEmpty()) {
        emptyList()
    } else {
        List(minOf(RECOMMENDATION_BATCH_SIZE, available.size)) { index ->
            available[(batch * RECOMMENDATION_BATCH_SIZE + index) % available.size]
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            !connectorsLoaded -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            available.isEmpty() -> item {
                EmptyCard(
                    stringResource(R.string.tasks_recommended_empty_title),
                    stringResource(R.string.tasks_recommended_empty_body),
                )
            }
            else -> items(visible, key = { it.first.id }) { (recommendation, matches) ->
                val alreadyAdded = tasks.any { it.title == stringResource(recommendation.titleRes) }
                RecommendationCard(
                    recommendation = recommendation,
                    connectorNames = matches.map { it.displayName?.takeIf(String::isNotBlank) ?: it.kind },
                    alreadyAdded = alreadyAdded,
                    onAdd = { onAdd(recommendation, matches) },
                )
            }
        }
        if (connectorsLoaded && available.isNotEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { batch++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tasks_recommended_shuffle))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: TaskRecommendation,
    connectorNames: List<String>,
    alreadyAdded: Boolean,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = recommendationColor(recommendation.kind)) {
                Icon(
                    recommendation.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(recommendation.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(recommendation.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    stringResource(R.string.tasks_recommended_via, connectorNames.joinToString(" / ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(10.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.clickable(enabled = !alreadyAdded, onClick = onAdd),
            ) {
                if (alreadyAdded) {
                    Icon(
                        Icons.Filled.TaskAlt,
                        stringResource(R.string.tasks_recommended_added),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(13.dp).size(22.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Add,
                        stringResource(R.string.tasks_recommended_add),
                        modifier = Modifier.padding(13.dp).size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun recommendationColor(kind: RecommendationKind): Color = when (kind) {
    RecommendationKind.NEWS -> MaterialTheme.colorScheme.primaryContainer
    RecommendationKind.MAIL -> MaterialTheme.colorScheme.secondaryContainer
    RecommendationKind.READING -> MaterialTheme.colorScheme.tertiaryContainer
    RecommendationKind.WEATHER -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun TaskCard(
    task: AutomationTaskEntity,
    onClick: () -> Unit,
) {
    val paused = task.status == AutomationTaskStatus.PAUSED
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.prompt.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = task.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    humanSchedule(task.scheduleRule),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(14.dp))
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = when {
                    task.lastStatus == TASK_STATUS_ERROR -> MaterialTheme.colorScheme.error
                    paused -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.primary
                }
            ) {}
        }
    }
}

@Composable
private fun NaturalTaskPage(
    initialDescription: String,
    target: AutomationTaskEntity?,
    processing: Boolean,
    error: com.octopmobile.app.data.remote.ApiError?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var description by rememberSaveable(initialDescription) { mutableStateOf(initialDescription) }
    val focusRequester = remember { FocusRequester() }
    val examples = if (target == null) {
        listOf(
            Icons.Filled.AutoAwesome to stringResource(R.string.tasks_natural_example_mail),
            Icons.Filled.AutoAwesome to stringResource(R.string.tasks_natural_example_calendar),
            Icons.Filled.Celebration to stringResource(R.string.tasks_natural_example_weekend),
        )
    } else {
        listOf(
            Icons.Filled.AutoAwesome to stringResource(R.string.tasks_natural_edit_time),
            Icons.Filled.AutoAwesome to stringResource(
                if (target.status == AutomationTaskStatus.PAUSED) R.string.tasks_natural_edit_resume else R.string.tasks_natural_edit_pause,
            ),
            Icons.Filled.AutoAwesome to stringResource(R.string.tasks_natural_edit_delete),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.46f)) {
                IconButton(onClick = onDismiss, enabled = !processing) {
                    Icon(
                        Icons.Filled.Close,
                        stringResource(
                            if (target == null) R.string.tasks_natural_close else R.string.tasks_natural_edit_close,
                        ),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (target == null) R.string.tasks_natural_page_title else R.string.tasks_natural_edit_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = if (target == null) {
                Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp)
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(start = 18.dp, end = 18.dp, top = 92.dp, bottom = 132.dp)
                    .verticalScroll(rememberScrollState())
            },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            target?.let {
                CurrentTaskCard(it)
            }
            examples.forEach { (icon, text) ->
                Surface(
                    modifier = Modifier.clickable(enabled = !processing) {
                        description = text
                        focusRequester.requestFocus()
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (processing || error != null) {
                Text(
                    stringResource(
                        when {
                            error != null && target != null -> R.string.tasks_natural_edit_failed
                            error != null -> R.string.tasks_natural_failed
                            target == null -> R.string.tasks_natural_creating
                            else -> R.string.tasks_natural_updating
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 5.dp,
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    enabled = !processing,
                    placeholder = {
                        Text(stringResource(if (target == null) R.string.tasks_natural_hint else R.string.tasks_natural_edit_hint))
                    },
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(28.dp),
                    trailingIcon = {
                        Surface(
                            shape = CircleShape,
                            color = if (description.isNotBlank() && !processing) {
                                MaterialTheme.colorScheme.onSurface
                            } else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            IconButton(
                                onClick = { onSubmit(description) },
                                enabled = description.isNotBlank() && !processing,
                            ) {
                                if (processing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        stringResource(if (target == null) R.string.tasks_natural_send else R.string.tasks_natural_edit_send),
                                        tint = if (description.isNotBlank()) MaterialTheme.colorScheme.surface else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
            }
        }
    }
}

@Composable
private fun CurrentTaskCard(task: AutomationTaskEntity) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.48f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                humanSchedule(task.scheduleRule),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.tasks_prompt_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                task.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

internal fun humanSchedule(raw: String): String = when (val rule = ScheduleRules.parse(raw)) {
    is ScheduleRule.Cron -> humanCron(rule.fields) ?: raw
    is ScheduleRule.Interval -> when {
        rule.seconds % 86_400L == 0L -> "每 ${rule.seconds / 86_400L} 天"
        rule.seconds % 3_600L == 0L -> "每 ${rule.seconds / 3_600L} 小时"
        rule.seconds % 60L == 0L -> "每 ${rule.seconds / 60L} 分钟"
        else -> "每 ${rule.seconds} 秒"
    }
    is ScheduleRule.Once -> rule.epochMillis?.let {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    } ?: raw
    is ScheduleRule.Event -> "事件触发"
    is ScheduleRule.Unknown -> rule.raw
}

private fun humanCron(fields: List<String>): String? {
    val minute = fields[0].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val hour = fields[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val time = "%02d:%02d".format(hour, minute)
    return when {
        fields.drop(2).all { it == "*" } -> "每天 $time"
        fields[2] == "*" && fields[3] == "*" && fields[4].toIntOrNull() in 0..7 -> {
            val weekday = listOf("日", "一", "二", "三", "四", "五", "六")[fields[4].toInt() % 7]
            "每周$weekday $time"
        }
        fields[2].toIntOrNull() in 1..31 && fields[3] == "*" && fields[4] == "*" -> {
            "每月 ${fields[2]} 日 $time"
        }
        else -> null
    }
}

private const val TASK_STATUS_ERROR = "error"
private const val RECOMMENDATION_BATCH_SIZE = 3
