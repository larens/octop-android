package com.octopmobile.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.octopmobile.app.OctopApp
import com.octopmobile.app.ui.theme.OctopTheme

/**
 * 原生客户端入口。单 Activity + Compose 导航（docs/spec.md §7）。
 *
 * 冷启动不显示全屏转圈：先画一帧纯背景，等本地配置读完再决定进连接向导还是会话列表
 * （docs/spec.md §12）。WebView 兜底页见 [com.octopmobile.app.ui.web.WebViewActivity]。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as OctopApp).container

        setContent {
            OctopTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val host = container.prefs.currentHost()
                    startDestination = when {
                        // 没配地址 → 连接向导；配了但没令牌 → 登录；都有 → 直接进列表
                        host.isNullOrBlank() -> Routes.CONNECT
                        container.securePrefs.tokenFor(host) == null -> Routes.LOGIN
                        else -> Routes.THREADS
                    }
                }

                val destination = startDestination
                if (destination == null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) { /* 冷启动占位帧，故意留空 */ }
                } else {
                    OctopNavHost(startDestination = destination)
                }
            }
        }
    }
}
