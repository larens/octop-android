package com.octopmobile.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.octopmobile.app.OctopApp
import com.octopmobile.app.ui.theme.OctopTheme
import com.octopmobile.app.ui.workbench.WorkbenchTab
import com.octopmobile.app.ui.workbench.routeValue
import kotlinx.coroutines.flow.first

/**
 * 原生客户端入口。单 Activity + Compose 导航（docs/spec.md §7）。
 *
 * 冷启动不显示全屏转圈：先画一帧纯背景，等本地配置读完再决定进连接向导还是会话列表
 * （docs/spec.md §12）。WebView 兜底页见 [com.octopmobile.app.ui.web.WebViewActivity]。
 *
 * 除了深链，这里还接住三样系统入口（docs/spec.md §14）：通知点击（深链）、
 * App Shortcuts、其他 App 的分享。后两者不直接导航 —— 它们的终点是一个还不存在的会话，
 * 所以统一放进 [PendingHandoff]，交给工作台「会话」栏建出会话后消费。
 */
class MainActivity : ComponentActivity() {

    /**
     * 待处理的深链（docs/spec.md §14）。声明成状态字段而不是只在 `onCreate` 里读一次：
     * `singleTask` 下 App 已经在栈里时点通知不会再走 `onCreate`，得由 [onNewIntent] 续上。
     */
    private var deepLink by mutableStateOf<Uri?>(null)
    private var shareEntryPending by mutableStateOf(false)

    /**
     * 任务进度通知的运行时申请（Android 13+）。被拒绝不做任何补救 ——
     * 通知只是提示，服务照跑（docs/spec.md §15 标的是「可选可拒」）。
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 静默降级 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deepLink = intent?.data
        handleSystemEntry(intent)
        requestNotificationPermission()

        val container = (application as OctopApp).container

        setContent {
            OctopTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }
                var startWorkbenchTab by remember { mutableStateOf<WorkbenchTab?>(null) }
                var pendingRoute by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val host = container.prefs.currentHost()
                    startDestination = when {
                        // 没配地址 → 连接向导；配了但没令牌 → 登录；都有 → 直接进工作台。
                        // 默认落在 Today，先看今天值得处理什么。
                        host.isNullOrBlank() -> Routes.CONNECT
                        container.securePrefs.tokenFor(host) == null -> Routes.LOGIN
                        else -> Routes.THREADS_PATTERN
                    }
                    startWorkbenchTab = if (
                        startDestination == Routes.THREADS_PATTERN &&
                        Routes.todayCardIdFromDeepLink(deepLink) != null
                    ) {
                        WorkbenchTab.TODAY
                    } else {
                        WorkbenchTab.TODAY
                    }
                }

                // 深链要等 startDestination 定下来才知道能不能直达：向导页/登录页里没有可落脚的
                // 会话列表，这时丢掉比强行跳过去更合理。agent 取当前选中那个 —— 深链只带会话 id。
                LaunchedEffect(deepLink, startDestination) {
                    val threadId = Routes.threadIdFromDeepLink(deepLink)
                    val todayCardId = Routes.todayCardIdFromDeepLink(deepLink)
                    if (threadId == null && todayCardId == null) return@LaunchedEffect
                    val destination = startDestination ?: return@LaunchedEffect
                    // 先消费掉：接下来无论跳不跳，这次深链都算处理完了
                    deepLink = null
                    if (destination != Routes.THREADS_PATTERN) return@LaunchedEffect

                    pendingRoute = if (todayCardId != null) {
                        PendingTodayCard.arm(todayCardId)
                        // 冷启动已经直接落在 Today 时不需要再导航一次：会话栏的默认入口会
                        // 自动建会话，和深链抢导航会把它重新盖回“新会话”。
                        if (startWorkbenchTab == WorkbenchTab.TODAY) {
                            null
                        } else {
                            Routes.threads(WorkbenchTab.TODAY.routeValue())
                        }
                    } else {
                        val agentId = container.prefs.selectedAgentId.first() ?: return@LaunchedEffect
                        Routes.chat(agentId, requireNotNull(threadId))
                    }
                }

                // 从任意页面收到系统分享都先回到工作台，让用户选择“问 Agent”或“存入收集箱”。
                LaunchedEffect(shareEntryPending, startDestination, pendingRoute) {
                    if (!shareEntryPending) return@LaunchedEffect
                    val destination = startDestination ?: return@LaunchedEffect
                    shareEntryPending = false
                    if (destination == Routes.THREADS_PATTERN && pendingRoute == null) {
                        pendingRoute = Routes.threads(com.octopmobile.app.ui.workbench.WorkbenchTab.TODAY.routeValue())
                    }
                }

                val destination = startDestination
                val initialWorkbenchTab = startWorkbenchTab
                if (destination == null || initialWorkbenchTab == null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) { /* 冷启动占位帧，故意留空 */ }
                } else {
                    OctopNavHost(
                        startDestination = destination,
                        initialWorkbenchTab = initialWorkbenchTab,
                        pendingRoute = pendingRoute,
                        onPendingRouteConsumed = { pendingRoute = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 只认深链：从 WebView 兜底页 startActivity 回来时 intent 里没有 data，不该被清掉
        if (Routes.threadIdFromDeepLink(intent.data) != null || Routes.todayCardIdFromDeepLink(intent.data) != null) {
            deepLink = intent.data
        }
        handleSystemEntry(intent)
    }

    /**
     * 接住 App Shortcuts 与分享。二者都是「起 App 之后还要做点什么」，交给会话列表页
     * 消费（那里才有建会话的能力），这里只负责把内容从 Intent 里取出来。
     */
    private fun handleSystemEntry(intent: Intent?) {
        intent ?: return

        val shortcut = AppActions.from(intent.action)
        if (shortcut != null) {
            PendingHandoff.arm(Handoff(action = shortcut))
            return
        }

        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            val uris = sharedUris(intent)
            // 纯空分享（既没文字也没附件）不当作一次交接，免得建出一个空会话
            if (text == null && uris.isEmpty()) return
            PendingShareHandoff.arm(Handoff(text = text, uris = uris, action = AppActions.SHARE))
            shareEntryPending = true
        }
    }

    /**
     * 分享进来的附件。`ACTION_SEND` 是单个 `Uri`、`ACTION_SEND_MULTIPLE` 是 `ArrayList<Uri>`，
     * 但两者共用 `EXTRA_STREAM` 这一个键 —— 直接取原始对象判类型，比按 SDK 版本分支读更稳。
     * 这里刻意用被标记为 deprecated 的原始读取：类型化 API 会按调用方的期望类型强转，
     * 而我们要的就是「拿到什么类型就按什么类型处理」。
     */
    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val extraUris = when (val raw = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> listOf(raw)
            is ArrayList<*> -> raw.filterIsInstance<Uri>()
            else -> emptyList()
        }
        val clipUris = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        return (extraUris + clipUris).distinct()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
