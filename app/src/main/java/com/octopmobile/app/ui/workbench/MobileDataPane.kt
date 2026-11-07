package com.octopmobile.app.ui.workbench

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MobileEventEntity
import com.octopmobile.app.data.db.MobileSourceEntity
import com.octopmobile.app.data.mobile.AndroidAppConnectorAccess
import java.text.DateFormat
import java.util.Date

@Composable
fun MobileDataPane(
    onBack: () -> Unit,
    cacheBytes: Long? = null,
    onClearCache: (() -> Unit)? = null,
) {
    val viewModel: MobileDataViewModel = viewModel(factory = MobileDataViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var platformId by rememberSaveable { mutableStateOf(ConnectorPlatform.ANDROID.name) }
    val platform = ConnectorPlatform.valueOf(platformId)
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAddInfo by rememberSaveable { mutableStateOf(false) }
    var permissionSource by remember { mutableStateOf<String?>(null) }
    var specialAccessSource by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val sourceType = permissionSource
        permissionSource = null
        if (sourceType != null) {
            viewModel.onRuntimePermissionResult(context, sourceType, results)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, specialAccessSource) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                specialAccessSource?.let { sourceType ->
                    specialAccessSource = null
                    viewModel.onSpecialAccessReturned(context, sourceType)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connectors = appConnectorCatalog.filter { it.platform == platform }
    val localizedLabels = connectors.associateWith {
        "${stringResource(it.titleRes)} ${stringResource(it.descriptionRes)}"
    }
    val visibleConnectors = connectors.filter { definition ->
        searchQuery.isBlank() ||
            localizedLabels.getValue(definition).contains(searchQuery.trim(), ignoreCase = true)
    }

    val connect: (AppConnectorDefinition) -> Unit = definition@{ definition ->
        val sourceType = definition.sourceType ?: return@definition
        if (!definition.available) return@definition
        val runtimePermissions = AndroidAppConnectorAccess.runtimePermissions(sourceType)
        when {
            runtimePermissions.isNotEmpty() &&
                !AndroidAppConnectorAccess.runtimeAccessGranted(context, sourceType) -> {
                permissionSource = sourceType
                permissionLauncher.launch(runtimePermissions.toTypedArray())
            }
            !AndroidAppConnectorAccess.hasAccess(context, sourceType) -> {
                val intent = AndroidAppConnectorAccess.specialAccessSettings(context, sourceType)
                if (intent == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.connector_settings_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    specialAccessSource = sourceType
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            specialAccessSource = null
                            Toast.makeText(
                                context,
                                context.getString(R.string.connector_settings_unavailable),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                }
            }
            else -> viewModel.onSpecialAccessReturned(context, sourceType)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ConnectorHeader(
                searchOpen = searchOpen,
                onBack = onBack,
                onToggleSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchQuery = ""
                },
                onAdd = { showAddInfo = true },
            )
        }

        if (searchOpen) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    placeholder = { Text(stringResource(R.string.connector_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
            }
        }

        item {
            ConnectorPlatformTabs(
                selected = platform,
                onSelect = {
                    platformId = it.name
                    searchQuery = ""
                },
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(platform.sectionRes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (visibleConnectors.isEmpty()) {
            item {
                EmptyCard(
                    stringResource(R.string.connector_search_empty_title),
                    stringResource(R.string.connector_search_empty_body),
                )
            }
        } else {
            items(visibleConnectors, key = { it.id }) { definition ->
                val source = definition.sourceType?.let { sourceType ->
                    state.sources.firstOrNull { it.sourceType == sourceType }
                }
                AppConnectorCard(
                    definition = definition,
                    source = source,
                    enabled = source?.enabled == true,
                    onConnect = { connect(definition) },
                    onDisconnect = {
                        definition.sourceType?.let(viewModel::setDisabled)
                    },
                    onRefresh = {
                        definition.sourceType?.let { viewModel.refreshSource(context, it) }
                    },
                )
            }
        }

        if (platform != ConnectorPlatform.ANDROID) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.connector_companion_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            stringResource(R.string.connector_companion_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.mobile_data_privacy_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.mobile_data_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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

        item {
            SectionTitle(
                stringResource(R.string.mobile_data_preview_title),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
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

        if (onClearCache != null) {
            item {
                SectionTitle(
                    stringResource(R.string.mobile_data_storage_title),
                    Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_clear_cache),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.mobile_data_cache_summary,
                                    cacheBytes?.let(::formatMobileCacheBytes)
                                        ?: stringResource(R.string.settings_cache_measuring),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onClearCache, enabled = cacheBytes != null) {
                            Text(stringResource(R.string.mobile_data_clear_cache_action))
                        }
                    }
                }
            }
        }
    }

    if (showAddInfo) {
        AlertDialog(
            onDismissRequest = { showAddInfo = false },
            title = { Text(stringResource(R.string.connector_add_title)) },
            text = { Text(stringResource(R.string.connector_add_body)) },
            confirmButton = {
                TextButton(onClick = { showAddInfo = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

@Composable
private fun ConnectorHeader(
    searchOpen: Boolean,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                stringResource(R.string.mobile_data_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.mobile_data_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.connector_add_title))
        }
        IconButton(onClick = onToggleSearch) {
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(
                    if (searchOpen) R.string.connector_search_close else R.string.connector_search_open,
                ),
            )
        }
    }
}

@Composable
private fun ConnectorPlatformTabs(
    selected: ConnectorPlatform,
    onSelect: (ConnectorPlatform) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ConnectorPlatform.entries.forEach { platform ->
            val isSelected = platform == selected
            Surface(
                onClick = { onSelect(platform) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                },
                tonalElevation = if (isSelected) 2.dp else 0.dp,
            ) {
                Text(
                    stringResource(platform.labelRes),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun AppConnectorCard(
    definition: AppConnectorDefinition,
    source: MobileSourceEntity?,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    val title = stringResource(definition.titleRes)
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clickable(enabled = enabled) { onRefresh() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(definition.tint.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    definition.icon,
                    contentDescription = null,
                    tint = definition.tint,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(definition.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (enabled) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        connectorStatus(source),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source?.lastError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color(0xFF2F8A4C)
                        },
                    )
                }
            }
            when {
                !definition.available -> {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            stringResource(R.string.connector_planned_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                enabled -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(
                                    R.string.connector_refresh_description,
                                    title,
                                ),
                            )
                        }
                        IconButton(
                            onClick = onDisconnect,
                            modifier = Modifier.background(Color(0xFFDFF4E5), CircleShape),
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(
                                    R.string.connector_disconnect_description,
                                    title,
                                ),
                                tint = Color(0xFF2F8A4C),
                            )
                        }
                    }
                }
                else -> {
                    IconButton(
                        onClick = onConnect,
                        modifier = Modifier.background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            CircleShape,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(
                                R.string.connector_connect_description,
                                title,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun connectorStatus(source: MobileSourceEntity?): String = when {
    source?.lastError != null -> source.lastError
    source?.lastSyncAt != null -> stringResource(
        R.string.connector_status_connected_at,
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(source.lastSyncAt)),
    )
    else -> stringResource(R.string.connector_status_connected)
}

private fun formatMobileCacheBytes(bytes: Long): String =
    java.util.Locale.US.let { locale -> String.format(locale, "%.1f MB", bytes / 1024.0 / 1024.0) }

@Composable
private fun MobileEventCard(event: MobileEventEntity, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.labelMedium)
                Text(event.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.occurredAt))} · ${
                        if (event.uploadState == "uploaded") {
                            stringResource(R.string.mobile_event_uploaded)
                        } else {
                            stringResource(R.string.mobile_event_pending)
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.uploadState != "uploaded") {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                    )
                }
            }
        }
    }
}
