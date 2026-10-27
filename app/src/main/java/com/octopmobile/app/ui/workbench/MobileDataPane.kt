package com.octopmobile.app.ui.workbench

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MobileEventEntity
import com.octopmobile.app.data.db.MobileSourceEntity
import com.octopmobile.app.data.db.MobileSourceType
import java.text.DateFormat
import java.util.Date

@Composable
fun MobileDataPane(onBack: () -> Unit) {
    val viewModel: MobileDataViewModel = viewModel(factory = MobileDataViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onCalendarPermission(context, it)
    }
    val healthPermissions = rememberLauncherForActivityResult(viewModel.healthPermissionContract()) {
        viewModel.onHealthPermissions(context, it)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mobile_data_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.mobile_data_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.mobile_data_privacy_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.mobile_data_privacy_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::upload, enabled = !state.busy && state.pendingCount > 0) {
                        Text(stringResource(R.string.mobile_data_sync, state.pendingCount))
                    }
                    state.notice?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.mobile_data_sources_title), Modifier.padding(horizontal = 16.dp)) }
        items(state.sources, key = { it.sourceType }) { source ->
            MobileSourceCard(
                source = source,
                onEnable = {
                    when (source.sourceType) {
                        MobileSourceType.SYSTEM_CALENDAR -> calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        MobileSourceType.HEALTH_CONNECT -> {
                            if (androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) ==
                                androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE
                            ) {
                                healthPermissions.launch(viewModel.healthPermissions())
                            } else {
                                viewModel.collectHealth(context)
                            }
                        }
                    }
                },
                onDisable = { viewModel.setDisabled(source.sourceType) },
                onRefresh = {
                    when (source.sourceType) {
                        MobileSourceType.SYSTEM_CALENDAR -> viewModel.collectCalendar(context)
                        MobileSourceType.HEALTH_CONNECT -> viewModel.collectHealth(context)
                    }
                },
            )
        }

        item { SectionTitle(stringResource(R.string.mobile_data_preview_title), Modifier.padding(horizontal = 16.dp)) }
        if (state.events.isEmpty()) {
            item {
                EmptyCard(
                    stringResource(R.string.mobile_data_preview_empty_title),
                    stringResource(R.string.mobile_data_preview_empty_body),
                )
            }
        } else {
            items(state.events, key = { it.id }) { event ->
                MobileEventCard(event, onDelete = { viewModel.deleteEvent(event.id) })
            }
        }
    }
}

@Composable
private fun MobileSourceCard(
    source: MobileSourceEntity,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sourceName(source.sourceType), style = MaterialTheme.typography.titleSmall)
                Text(
                    sourceStatus(source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (source.sourceType != MobileSourceType.SHARE_INBOX && source.enabled) {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = null) }
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = { enabled -> if (enabled) onEnable() else onDisable() },
                enabled = source.sourceType != MobileSourceType.SHARE_INBOX,
            )
        }
    }
}

@Composable
private fun MobileEventCard(event: MobileEventEntity, onDelete: () -> Unit) {
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.labelMedium)
                Text(event.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.occurredAt))} · ${if (event.uploadState == "uploaded") "已同步" else "待同步"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.uploadState != "uploaded") {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun sourceName(sourceType: String): String = when (sourceType) {
    MobileSourceType.SYSTEM_CALENDAR -> stringResource(R.string.mobile_source_calendar)
    MobileSourceType.HEALTH_CONNECT -> stringResource(R.string.mobile_source_health)
    else -> stringResource(R.string.mobile_source_share)
}

private fun sourceStatus(source: MobileSourceEntity): String = when {
    source.lastError != null -> source.lastError
    source.lastSyncAt != null -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(source.lastSyncAt))
    source.enabled -> "已开启，尚未采集"
    source.permissionState == "denied" -> "权限未授予"
    else -> "未开启"
}
