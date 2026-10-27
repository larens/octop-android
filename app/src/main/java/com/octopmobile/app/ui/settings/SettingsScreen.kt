package com.octopmobile.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octopmobile.app.BuildConfig
import com.octopmobile.app.Const
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.ui.workbench.MobileDataPane
import com.octopmobile.app.ui.web.WebViewActivity
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 设置（docs/spec.md §6 `ui/settings`）。
 *
 * 实例地址展示 / 修改、WebView 兜底入口、退出登录（清令牌 + 本地镜像 + Cookie）、关于。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onHostCleared: () -> Unit,
    onLoggedOut: () -> Unit,
    onOpenMobileData: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf<String?>(null) }
    /** 附件缓存占用，null 表示还没算出来。 */
    var cacheBytes by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        host = container.prefs.currentHost()
        cacheBytes = container.attachments.totalBytes()
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun openWebFallback() {
        if (host.isNullOrBlank()) {
            toast(context.getString(R.string.err_not_configured))
            return
        }
        context.startActivity(Intent(context, WebViewActivity::class.java))
    }

    /**
     * 退出登录 = 清令牌 + 清本地镜像 + 清 WebView 的 Cookie/WebStorage（docs/spec.md §15）。
     *
     * 本地缓存必须一起清：否则换个账号登录会先看到上一个账号的会话列表（那是错的数据，
     * 不是「稍后会刷新的旧数据」）。地址保留，用户重新登录即可继续用同一台实例。
     */
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
            toast(context.getString(R.string.settings_logged_out))
            onLoggedOut()
        }
    }

    /**
     * 清理附件缓存（docs/spec.md §13、§19 第 19 条）。
     *
     * 只删附件二进制与索引，**不动**登录态、会话与消息 —— 清完 App 仍然可用，
     * 已经渲染过的图片会按需重新拉取。
     */
    fun clearCache() {
        scope.launch {
            container.attachments.clear()
            cacheBytes = container.attachments.totalBytes()
            toast(context.getString(R.string.settings_cache_cleared))
        }
    }

    fun openProjectHome() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.PROJECT_URL)))
        } catch (e: ActivityNotFoundException) {
            toast(context.getString(R.string.err_no_app, "https"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_instance))

            Text(
                text = stringResource(R.string.settings_host_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = host ?: stringResource(R.string.settings_no_host),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(8.dp))

            ActionRow(
                title = stringResource(R.string.settings_change_host),
                onClick = {
                    scope.launch {
                        container.prefs.setHost(null)
                        onHostCleared()
                    }
                },
            )

            ActionRow(
                title = stringResource(R.string.settings_web_fallback),
                subtitle = stringResource(R.string.settings_web_fallback_summary),
                onClick = { openWebFallback() },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader(stringResource(R.string.settings_data_sources))

            ActionRow(
                title = stringResource(R.string.settings_mobile_data_title),
                subtitle = stringResource(R.string.settings_mobile_data_summary),
                onClick = onOpenMobileData,
            )

            ActionRow(
                title = stringResource(R.string.settings_clear_cache),
                subtitle = stringResource(
                    R.string.settings_clear_cache_summary,
                    cacheBytes?.let(::formatBytes) ?: stringResource(R.string.settings_cache_measuring),
                ),
                onClick = { clearCache() },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader(stringResource(R.string.settings_account))

            ActionRow(
                title = stringResource(R.string.settings_logout),
                subtitle = stringResource(R.string.settings_logout_summary),
                onClick = { logout() },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader(stringResource(R.string.settings_about))

            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            ActionRow(
                title = stringResource(R.string.settings_project),
                subtitle = Const.PROJECT_URL,
                onClick = { openProjectHome() },
            )

            Text(
                text = stringResource(R.string.settings_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
fun MobileDataSettingsScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MobileDataPane(onBack = onBack)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
    )
}

/**
 * 缓存占用的可读文本。
 *
 * 用 1024 进制（磁盘占用的常规口径），并保留一位小数 —— 清缓存的判断只需要看到量级，
 * 不需要精确到字节。
 */
private fun formatBytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

@Composable
private fun ActionRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
