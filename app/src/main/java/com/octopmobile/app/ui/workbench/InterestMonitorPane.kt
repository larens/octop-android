package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.remote.dto.InterestMonitorDto
import com.octopmobile.app.data.remote.dto.InterestMonitorSchedule
import com.octopmobile.app.data.remote.dto.TodayGoalDto
import com.octopmobile.app.ui.asText

@Composable
fun InterestMonitorPane(onBack: () -> Unit, onCardsMayChange: () -> Unit, onNeedsLogin: () -> Unit) {
    val viewModel: InterestMonitorViewModel = viewModel(factory = InterestMonitorViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var description by rememberSaveable { mutableStateOf("") }
    var schedule by rememberSaveable { mutableStateOf(InterestMonitorSchedule.MORNING) }
    var selectedGoalId by rememberSaveable { mutableStateOf<String?>(null) }
    var goalTitle by rememberSaveable { mutableStateOf("") }
    var goalDefinition by rememberSaveable { mutableStateOf("") }
    var goalSuccess by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.needsLogin) { if (state.needsLogin) onNeedsLogin() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.interest_monitor_title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            Text(
                stringResource(R.string.interest_monitor_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SectionTitle(stringResource(R.string.today_goals_title)) }
        if (state.goals.isEmpty()) {
            item { Text(stringResource(R.string.today_goals_empty), style = MaterialTheme.typography.bodySmall) }
        } else {
            items(state.goals, key = { "goal-${it.goalId}" }) { goal ->
                GoalCard(
                    goal = goal,
                    busy = state.busy,
                    onPause = { viewModel.setGoalStatus(goal, if (goal.status == "active") "paused" else "active") },
                    onConfirm = { viewModel.reconfirmGoal(goal) },
                )
            }
        }
        item {
            OutlinedTextField(
                value = goalTitle,
                onValueChange = { goalTitle = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.today_goal_title_label)) },
            )
        }
        item {
            OutlinedTextField(
                value = goalDefinition,
                onValueChange = { goalDefinition = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.today_goal_definition_label)) },
                minLines = 2,
            )
        }
        item {
            OutlinedTextField(
                value = goalSuccess,
                onValueChange = { goalSuccess = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.today_goal_success_label)) },
                minLines = 2,
            )
        }
        item {
            Button(
                onClick = {
                    viewModel.createGoal(goalTitle, goalDefinition, goalSuccess, 30)
                    goalTitle = ""
                    goalDefinition = ""
                    goalSuccess = ""
                },
                enabled = !state.busy && goalTitle.trim().length >= 2 &&
                    goalDefinition.trim().length >= 3 && goalSuccess.trim().length >= 3,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.today_goal_create)) }
        }
        item { SectionTitle(stringResource(R.string.interest_monitor_title)) }
        item {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.interest_monitor_description)) },
                supportingText = { Text(stringResource(R.string.interest_monitor_example)) },
                minLines = 2,
                maxLines = 5,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                scheduleOptions().forEach { (value, label) ->
                    FilterChip(selected = schedule == value, onClick = { schedule = value }, label = { Text(label) })
                }
            }
        }
        if (state.goals.any { it.status == "active" }) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.interest_monitor_goal_label), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedGoalId == null,
                            onClick = { selectedGoalId = null },
                            label = { Text(stringResource(R.string.interest_monitor_goal_none)) },
                        )
                        state.goals.filter { it.status == "active" }.forEach { goal ->
                            FilterChip(
                                selected = selectedGoalId == goal.goalId,
                                onClick = { selectedGoalId = goal.goalId },
                                label = { Text(goal.title) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { viewModel.create(description, schedule, selectedGoalId) },
                enabled = !state.busy && description.trim().length >= 3,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.interest_monitor_create)) }
        }
        state.notice?.let { notice ->
            item {
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        state.error?.let { error ->
            item { Text(error.asText(), color = MaterialTheme.colorScheme.error) }
        }
        if (state.loaded && state.monitors.isEmpty()) {
            item { EmptyCard(stringResource(R.string.interest_monitor_empty), stringResource(R.string.interest_monitor_empty_body)) }
        }
        items(state.monitors, key = { it.monitorId }) { monitor ->
            InterestMonitorCard(
                monitor,
                busy = state.busy,
                onToggle = { viewModel.setEnabled(monitor, it) },
                onRun = { viewModel.run(monitor, onCardsMayChange) },
                onDelete = { viewModel.delete(monitor) },
            )
        }
    }
}

@Composable
private fun InterestMonitorCard(
    item: InterestMonitorDto,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.description, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = item.enabled, onCheckedChange = onToggle, enabled = !busy)
            }
            Text(
                monitorStatus(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.lastStatus == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.goalTitle?.let {
                Text(
                    stringResource(R.string.interest_monitor_goal_value, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.goalNeedsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.interest_monitor_delete)) }
                TextButton(onClick = onRun, enabled = !busy) { Text(stringResource(R.string.interest_monitor_run)) }
            }
        }
    }
}

@Composable
private fun GoalCard(goal: TodayGoalDto, busy: Boolean, onPause: () -> Unit, onConfirm: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(goal.title, style = MaterialTheme.typography.titleSmall)
            Text(goal.definition, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.today_goal_success_value, goal.successRules),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (goal.needsReview) {
                Text(stringResource(R.string.today_goal_needs_review), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (goal.needsReview) {
                    TextButton(onClick = onConfirm, enabled = !busy) {
                        Text(stringResource(R.string.today_goal_confirm))
                    }
                }
                TextButton(onClick = onPause, enabled = !busy) {
                    Text(stringResource(if (goal.status == "active") R.string.today_goal_pause else R.string.today_goal_resume))
                }
            }
        }
    }
}

@Composable
private fun monitorStatus(item: InterestMonitorDto): String = when {
    item.lastStatus == "error" -> stringResource(R.string.interest_monitor_error, item.lastError.orEmpty())
    item.deliveryBackoffUntil?.let { it * 1000 > System.currentTimeMillis() } == true ->
        stringResource(
            when (item.feedbackBackoffReason) {
                "too_frequent" -> R.string.interest_monitor_backoff_too_frequent
                "irrelevant" -> R.string.interest_monitor_backoff_irrelevant
                else -> R.string.interest_monitor_backoff
            },
        )
    !item.baselineEstablished -> stringResource(R.string.interest_monitor_waiting_baseline)
    else -> stringResource(R.string.interest_monitor_ready, item.lastResultCount)
}

@Composable
private fun scheduleOptions(): List<Pair<String, String>> = listOf(
    InterestMonitorSchedule.MORNING to stringResource(R.string.interest_monitor_morning),
    InterestMonitorSchedule.EVENING to stringResource(R.string.interest_monitor_evening),
    InterestMonitorSchedule.TWICE_DAILY to stringResource(R.string.interest_monitor_twice),
)
