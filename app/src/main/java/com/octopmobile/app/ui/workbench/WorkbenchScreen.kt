package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.octopmobile.app.R
import com.octopmobile.app.ui.threads.ThreadsScreen

/**
 * 工作台的四个落点（docs/spec.md §23）。
 *
 * 会话、Today、任务、记忆是**同一层**的四块，所以用底部导航切换，不再互相往对方页面的
 * 顶部放跳转按钮 —— 那种「页头放兄弟页面入口」的样式在只有两三个入口时还凑合，
 * 到四块之后就会把页头变成长满按钮的工具栏。
 */
enum class WorkbenchTab { CHATS, TODAY, TASKS, MEMORY }

/**
 * 原生客户端的根界面。
 *
 * 只有这一层持有 `Scaffold` 与底部导航；四个页面自己**不带**顶部栏（[ThreadsScreen] 除外 ——
 * 它的页头还兼着「新建会话 / 设置」两个动作，不是页面间跳转入口）。子页面用
 * [consumeWindowInsets] 吃掉外层已经用掉的系统栏间距，避免状态栏高度被算两遍。
 */
@Composable
fun WorkbenchScreen(
    onOpenThread: (agentId: String, threadId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
    // 记住选中的那一栏：从会话页返回时应该回到离开前的那一栏，而不是弹回会话列表
    var tab by rememberSaveable { mutableStateOf(WorkbenchTab.CHATS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                WorkbenchTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tab = item },
                        icon = { Icon(imageVector = item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when (tab) {
                WorkbenchTab.CHATS -> ThreadsScreen(
                    onOpenThread = onOpenThread,
                    onOpenSettings = onOpenSettings,
                    onOpenWeb = onOpenWeb,
                    onNeedsLogin = onNeedsLogin,
                )

                WorkbenchTab.TODAY -> TodayPane(
                    onOpenThread = onOpenThread,
                    onOpenMemory = { tab = WorkbenchTab.MEMORY },
                    onNeedsLogin = onNeedsLogin,
                )

                WorkbenchTab.TASKS -> TasksPane(onNeedsLogin = onNeedsLogin)

                WorkbenchTab.MEMORY -> MemoryPane()
            }
        }
    }
}

private val WorkbenchTab.labelRes: Int
    get() = when (this) {
        WorkbenchTab.CHATS -> R.string.tab_chats
        WorkbenchTab.TODAY -> R.string.today_title
        WorkbenchTab.TASKS -> R.string.tasks_title
        WorkbenchTab.MEMORY -> R.string.memory_title
    }

/** 这四个图标都在 `material-icons-core` / `material-icons-extended` 里现成可用。 */
private val WorkbenchTab.icon: ImageVector
    get() = when (this) {
        WorkbenchTab.CHATS -> Icons.AutoMirrored.Filled.Chat
        WorkbenchTab.TODAY -> Icons.Filled.Today
        WorkbenchTab.TASKS -> Icons.Filled.TaskAlt
        WorkbenchTab.MEMORY -> Icons.Filled.Psychology
    }
