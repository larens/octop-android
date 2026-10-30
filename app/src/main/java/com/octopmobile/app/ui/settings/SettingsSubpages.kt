package com.octopmobile.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.data.UrlNormalizer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()
    val host by container.prefs.host.collectAsState(initial = null)
    var address by remember(host) { mutableStateOf(host.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    SubpageScaffold(
        title = stringResource(R.string.settings_server),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.settings_server_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                error = null
                saved = false
            },
            label = { Text(stringResource(R.string.settings_host_label)) },
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when (val outcome = UrlNormalizer.normalize(address)) {
                    is UrlNormalizer.Outcome.Invalid -> error = when (outcome.reason) {
                        UrlNormalizer.Reason.EMPTY -> context.getString(R.string.err_empty)
                        UrlNormalizer.Reason.MALFORMED -> context.getString(R.string.err_bad_url)
                        UrlNormalizer.Reason.UNSUPPORTED_SCHEME -> context.getString(R.string.err_bad_scheme)
                        UrlNormalizer.Reason.HAS_PATH -> context.getString(R.string.err_has_path)
                    }
                    is UrlNormalizer.Outcome.Valid -> {
                        busy = true
                        scope.launch {
                            container.prefs.setHost(outcome.baseUrl)
                            container.securePrefs.clear()
                            container.database.clearAll()
                            busy = false
                            saved = true
                            error = null
                        }
                    }
                }
            },
            enabled = !busy && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_server_save))
        }
        if (saved) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_server_saved),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_server_relogin_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(
        initial = com.octopmobile.app.data.settings.AppSettings(
            themeMode = com.octopmobile.app.data.settings.ThemeMode.SYSTEM,
            themeColor = com.octopmobile.app.data.settings.ThemeColor.BLUE,
            fontScale = 1f,
            voiceReadAloud = false,
            notificationsEnabled = true,
            taskResultNotifications = true,
            confirmationNotifications = true,
            scheduledTaskNotifications = true,
        ),
    )
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { container.settings.setNotificationsEnabled(granted) }
    }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { container.settings.setNotificationsEnabled(true) }
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            scope.launch { container.settings.setNotificationsEnabled(true) }
        } else {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SubpageScaffold(
        title = stringResource(R.string.settings_notifications),
        onBack = onBack,
    ) {
        SettingsToggle(
            title = stringResource(R.string.settings_notification_master),
            subtitle = stringResource(R.string.settings_notification_master_summary),
            checked = settings.notificationsEnabled,
            onCheckedChange = { checked ->
                if (checked) requestPermission() else scope.launch { container.settings.setNotificationsEnabled(false) }
            },
        )
        HorizontalDividerLike()
        SettingsToggle(
            title = stringResource(R.string.settings_notification_task_result),
            subtitle = stringResource(R.string.settings_notification_task_result_summary),
            checked = settings.taskResultNotifications,
            enabled = settings.notificationsEnabled,
            onCheckedChange = { value -> scope.launch { container.settings.setTaskResultNotifications(value) } },
        )
        SettingsToggle(
            title = stringResource(R.string.settings_notification_confirmation),
            subtitle = stringResource(R.string.settings_notification_confirmation_summary),
            checked = settings.confirmationNotifications,
            enabled = settings.notificationsEnabled,
            onCheckedChange = { value -> scope.launch { container.settings.setConfirmationNotifications(value) } },
        )
        SettingsToggle(
            title = stringResource(R.string.settings_notification_scheduled),
            subtitle = stringResource(R.string.settings_notification_scheduled_summary),
            checked = settings.scheduledTaskNotifications,
            enabled = settings.notificationsEnabled,
            onCheckedChange = { value -> scope.launch { container.settings.setScheduledTaskNotifications(value) } },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_notification_background_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(
        initial = com.octopmobile.app.data.settings.AppSettings(
            themeMode = com.octopmobile.app.data.settings.ThemeMode.SYSTEM,
            themeColor = com.octopmobile.app.data.settings.ThemeColor.BLUE,
            fontScale = 1f,
            voiceReadAloud = false,
            notificationsEnabled = true,
            taskResultNotifications = true,
            confirmationNotifications = true,
            scheduledTaskNotifications = true,
        ),
    )

    SubpageScaffold(
        title = stringResource(R.string.settings_voice),
        onBack = onBack,
    ) {
        SettingsToggle(
            title = stringResource(R.string.settings_voice_read_aloud),
            subtitle = stringResource(R.string.settings_voice_read_aloud_summary),
            checked = settings.voiceReadAloud,
            onCheckedChange = { value -> scope.launch { container.settings.setVoiceReadAloud(value) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubpageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_cancel))
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HorizontalDividerLike() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
}
