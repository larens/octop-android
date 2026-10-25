package com.octopmobile.app.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.octopmobile.app.ui.chat.ChatScreen
import com.octopmobile.app.ui.connect.ConnectScreen
import com.octopmobile.app.ui.login.LoginScreen
import com.octopmobile.app.ui.settings.SettingsScreen
import com.octopmobile.app.ui.threads.ThreadsScreen
import com.octopmobile.app.ui.web.WebViewActivity

/**
 * 全应用唯一的导航图。连接向导、登录、会话列表、会话、设置都是全屏 destination，
 * 因此不用嵌套导航；平板分栏（`material3.adaptive`）留到 M4 再考虑。
 *
 * 登录这一跳由 [ThreadsScreen] 通过 `onNeedsLogin` 触发，不做成连接向导的下一步 ——
 * 「有没有令牌」是会话列表自己必须校验的事（冷启动、令牌被服务端换密钥废掉都是同一条路径）。
 */
@Composable
fun OctopNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val openWeb: () -> Unit = {
        context.startActivity(Intent(context, WebViewActivity::class.java))
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.CONNECT) {
            ConnectScreen(
                // 连上之后从这里退回栈底，避免用户按返回键又回到向导页
                onConnected = {
                    navController.navigate(Routes.THREADS) {
                        popUpTo(Routes.CONNECT) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.THREADS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onOpenWeb = openWeb,
            )
        }

        composable(Routes.THREADS) {
            ThreadsScreen(
                onOpenThread = { agentId, threadId ->
                    navController.navigate(Routes.chat(agentId, threadId))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenWeb = openWeb,
                // 用 THREADS 换 LOGIN：栈里不该留着已经作废的列表页
                onNeedsLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.THREADS) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.CHAT_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_AGENT_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_THREAD_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            ChatScreen(
                agentId = entry.arguments?.getString(Routes.ARG_AGENT_ID).orEmpty(),
                threadId = entry.arguments?.getString(Routes.ARG_THREAD_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                // 换实例等于换登录态，会话缓存必须整体重来，所以直接清栈回向导
                onHostCleared = {
                    navController.navigate(Routes.CONNECT) { popUpTo(0) }
                },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) { popUpTo(0) }
                },
            )
        }
    }
}
