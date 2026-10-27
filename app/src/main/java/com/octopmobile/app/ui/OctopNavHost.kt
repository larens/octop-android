package com.octopmobile.app.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.octopmobile.app.ui.settings.MobileDataSettingsScreen
import com.octopmobile.app.ui.settings.SettingsScreen
import com.octopmobile.app.ui.web.WebViewActivity
import com.octopmobile.app.ui.workbench.WorkbenchScreen
import com.octopmobile.app.ui.workbench.WorkbenchTab
import com.octopmobile.app.ui.workbench.routeValue
import com.octopmobile.app.ui.workbench.workbenchTabOf

/**
 * 全应用唯一的导航图。连接向导、登录、工作台、会话、设置都是全屏 destination，
 * 因此不用嵌套导航；平板分栏（`material3.adaptive`）留到之后再考虑。
 *
 * 登录这一跳由工作台会话栏通过 `onNeedsLogin` 触发，不做成连接向导的下一步 ——
 * 「有没有令牌」是工作台自己必须校验的事（冷启动、令牌被服务端换密钥废掉都是同一条路径）。
 *
 * [pendingRoute] 用于深链（docs/spec.md §14）：由 [MainActivity] 解析好一条完整路由塞进来，
 * 导航图只负责跳过去，不关心链接长什么样。
 */
@Composable
fun OctopNavHost(
    startDestination: String,
    initialWorkbenchTab: WorkbenchTab = WorkbenchTab.TODAY,
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {},
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

        // 会话 / Today / 任务 / 记忆是同一个工作台的四个栏，由底部导航切换（docs/spec.md §23），
        // 所以这里只注册一个 destination —— 栏间切换不进返回栈，返回键从任何一栏都退回上一级。
        val workbenchContent: @Composable (WorkbenchTab) -> Unit = { initialTab ->
            WorkbenchScreen(
                onOpenThread = { agentId, threadId ->
                    navController.navigate(Routes.chat(agentId, threadId))
                },
                onOpenFreshThread = { agentId, threadId ->
                    navController.navigate(Routes.chat(agentId, threadId)) {
                        // 新会话是程序直达页面：清掉一路上的列表/工作台，返回键不再走历史页。
                        popUpTo(0)
                    }
                },
                initialTab = initialTab,
                // 登录态失效时清掉当前工作台，避免登录后返回已经作废的页面
                onNeedsLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0)
                    }
                },
            )
        }

        composable(
            route = Routes.THREADS_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_TAB) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = initialWorkbenchTab.routeValue()
                },
            ),
        ) { entry ->
            workbenchContent(workbenchTabOf(entry.arguments?.getString(Routes.ARG_TAB)))
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
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenThread = { agentId, threadId ->
                    // 所有 chat 路由（chat/{agentId}/{threadId}）都是同一个 destination id，
                    // launchSingleTop 按 id 比较会把「切换会话」也当成重复导航吞掉，所以不能带它。
                    navController.navigate(Routes.chat(agentId, threadId))
                },
                onOpenWorkbenchTab = { tab ->
                    navController.navigate(Routes.threads(tab.routeValue())) {
                        launchSingleTop = true
                    }
                },
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
                onOpenMobileData = {
                    navController.navigate(Routes.SETTINGS_MOBILE_DATA)
                },
            )
        }

        composable(Routes.SETTINGS_MOBILE_DATA) {
            MobileDataSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }

    // 深链（docs/spec.md §14）。放在 NavHost 之后：图这时已经装好，navigate 才是安全的。
    // 跳完立刻消费掉，否则旋转屏幕重组时会再跳一次。
    if (pendingRoute != null) {
        LaunchedEffect(pendingRoute) {
            navController.navigate(pendingRoute)
            onPendingRouteConsumed()
        }
    }
}
