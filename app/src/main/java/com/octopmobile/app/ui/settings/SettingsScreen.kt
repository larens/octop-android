package com.octopmobile.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octopmobile.app.BuildConfig
import com.octopmobile.app.Const
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.data.settings.AppSettings
import com.octopmobile.app.data.settings.ThemeColor
import com.octopmobile.app.data.settings.ThemeMode
import com.octopmobile.app.ui.theme.color
import com.octopmobile.app.ui.web.WebViewActivity
import com.octopmobile.app.ui.workbench.MobileDataPane
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMobileData: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenNotifications: () -> Unit,
    titleRes: Int = R.string.settings_title,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val host by container.prefs.host.collectAsState(initial = null)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(titleRes)) },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup {
                AccountCard(
                    displayName = container.securePrefs.displayName ?: container.securePrefs.username,
                    username = container.securePrefs.username,
                    onClick = onOpenProfile,
                )
            }

            SettingsGroup {
                SettingsLink(
                    title = stringResource(R.string.settings_appearance),
                    onClick = onOpenAppearance,
                )
                SettingsLink(
                    title = stringResource(R.string.settings_mobile_data_title),
                    onClick = onOpenMobileData,
                )
                SettingsLink(
                    title = stringResource(R.string.settings_notification_title),
                    onClick = onOpenNotifications,
                )
            }

            SettingsGroup {
                SettingsLink(
                    title = stringResource(R.string.settings_about_title),
                    onClick = onOpenAbout,
                )
                SettingsValueRow(
                    title = stringResource(R.string.settings_current_version),
                    value = "v${BuildConfig.VERSION_NAME}",
                )
                SettingsLink(
                    title = stringResource(R.string.settings_web_fallback),
                    onClick = { openWebFallback(context, host) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SubpageScaffold(title = stringResource(R.string.settings_about_title), onBack = onBack) {
        SettingsGroup {
            SettingsDescription(stringResource(R.string.settings_about_description))
        }
        SettingsGroup {
            SettingsLink(
                title = stringResource(R.string.settings_project),
                onClick = { openProjectHome(context) },
            )
            SettingsValueRow(
                title = stringResource(R.string.settings_current_version),
                value = "v${BuildConfig.VERSION_NAME}",
            )
        }
        Text(
            text = stringResource(R.string.settings_copyright),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    onBack: () -> Unit,
    onEditServer: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()
    val host by container.prefs.host.collectAsState(initial = null)
    var logoutDialog by remember { mutableStateOf(false) }

    fun logout() {
        val baseUrl = host
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        scope.launch {
            if (baseUrl.isNullOrBlank()) {
                container.securePrefs.clear()
            } else {
                container.instanceRepository(baseUrl).logout()
            }
            container.database.clearAll()
            Toast.makeText(context, context.getString(R.string.settings_logged_out), Toast.LENGTH_SHORT).show()
            onLoggedOut()
        }
    }

    SubpageScaffold(title = stringResource(R.string.settings_profile), onBack = onBack) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(
                    displayName = container.securePrefs.displayName ?: container.securePrefs.username,
                    username = container.securePrefs.username,
                )
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        stringResource(R.string.settings_profile_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = container.securePrefs.displayName
                            ?: container.securePrefs.username
                            ?: stringResource(R.string.settings_no_account),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = container.securePrefs.username ?: stringResource(R.string.settings_no_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SettingsGroup {
            SettingsLink(
                title = stringResource(R.string.settings_host_label),
                subtitle = host ?: stringResource(R.string.settings_no_host),
                onClick = onEditServer,
            )
        }

        SettingsGroup {
            SettingsLink(
                title = stringResource(R.string.settings_logout),
                subtitle = stringResource(R.string.settings_logout_summary),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { logoutDialog = true },
            )
        }
    }

    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = { Text(stringResource(R.string.settings_logout_confirm)) },
            confirmButton = {
                Button(onClick = { logoutDialog = false; logout() }) {
                    Text(stringResource(R.string.settings_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { logoutDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = defaultSettings())

    SubpageScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        SegmentedRow(
            options = ThemeMode.entries.map { mode ->
                mode to stringResource(
                    when (mode) {
                        ThemeMode.SYSTEM -> R.string.settings_theme_system
                        ThemeMode.LIGHT -> R.string.settings_theme_light
                        ThemeMode.DARK -> R.string.settings_theme_dark
                    },
                )
            },
            selected = settings.themeMode,
            onSelect = { value -> scope.launch { container.settings.setThemeMode(value) } },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ThemeColorRow(settings.themeColor) {
            scope.launch { container.settings.setThemeColor(it) }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SliderRow(
            title = stringResource(R.string.settings_font_size),
            value = settings.fontScale,
            onValueChange = { scale -> scope.launch { container.settings.setFontScale(scale) } },
        )
    }
}

@Composable
private fun AccountCard(displayName: String?, username: String?, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(displayName = displayName, username = username)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    stringResource(R.string.settings_profile_account),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = displayName ?: username ?: stringResource(R.string.settings_no_account),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountAvatar(displayName: String?, username: String?) {
    Box(
        Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (displayName ?: username ?: "?").take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun SettingsLink(
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsValueRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun <T : Enum<T>> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (option, label) ->
            val selectedOption = option == selected
            Box(
                Modifier.weight(1f)
                    .background(
                        if (selectedOption) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        MaterialTheme.shapes.small,
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selectedOption) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeColorRow(selected: ThemeColor, onSelect: (ThemeColor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_theme_color),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        ThemeColor.entries.forEach { option ->
            Box(
                Modifier.padding(start = 6.dp)
                    .size(24.dp)
                    .background(option.color(), CircleShape)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected == option) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.surface, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0.85f..1.45f, steps = 5)
        Row {
            Text(
                stringResource(R.string.settings_font_small),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_font_large),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SubpageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
fun MobileDataSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var clearCacheDialog by remember { mutableStateOf(false) }

    suspend fun refreshCache() {
        cacheBytes = container.attachments.totalBytes()
    }

    LaunchedEffect(Unit) { refreshCache() }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            MobileDataPane(
                onBack = onBack,
                cacheBytes = cacheBytes,
                onClearCache = { clearCacheDialog = true },
            )
        }
    }

    if (clearCacheDialog) {
        AlertDialog(
            onDismissRequest = { clearCacheDialog = false },
            title = { Text(stringResource(R.string.settings_clear_cache_title)) },
            text = { Text(stringResource(R.string.settings_clear_cache_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        clearCacheDialog = false
                        scope.launch {
                            container.attachments.clear()
                            refreshCache()
                            Toast.makeText(context, context.getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Text(stringResource(R.string.mobile_data_clear_cache_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearCacheDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun defaultSettings() = AppSettings(
    themeMode = ThemeMode.SYSTEM,
    themeColor = ThemeColor.BLUE,
    fontScale = 1f,
    voiceReadAloud = false,
    notificationsEnabled = true,
    taskResultNotifications = true,
    confirmationNotifications = true,
    scheduledTaskNotifications = true,
)

private fun openWebFallback(context: Context, host: String?) {
    if (host.isNullOrBlank()) {
        Toast.makeText(context, context.getString(R.string.err_not_configured), Toast.LENGTH_SHORT).show()
        return
    }
    context.startActivity(Intent(context, WebViewActivity::class.java))
}

private fun openProjectHome(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.PROJECT_URL)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.err_no_app, "https"), Toast.LENGTH_SHORT).show()
    }
}
