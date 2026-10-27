package com.octopmobile.app.ui

import android.net.Uri

/**
 * 导航路由表（docs/spec.md §6 的 `ui/` 结构）。
 *
 * 工作台统一用带可选 tab 参数的 pattern，普通启动默认到会话，Today 深链可把
 * 默认栏设为 Today。`startDestination` 必须是注册过的完整 route pattern，不能只写
 * 一个无参数别名。
 */
object Routes {

    const val CONNECT = "connect"
    const val LOGIN = "login"
    const val THREADS = "threads"
    const val ARG_TAB = "tab"
    const val THREADS_PATTERN = "threads?$ARG_TAB={$ARG_TAB}"
    const val SETTINGS = "settings"
    const val SETTINGS_MOBILE_DATA = "settings/mobile-data"

    const val ARG_AGENT_ID = "agentId"
    const val ARG_THREAD_ID = "threadId"

    const val CHAT_PATTERN = "chat/{$ARG_AGENT_ID}/{$ARG_THREAD_ID}"

    /** 会话内的 `agent_id` 是业务字符串标识，不是服务端自增主键 `id`（见 docs/protocol.md §4）。 */
    fun chat(agentId: String, threadId: String): String = "chat/$agentId/$threadId"

    fun threads(tab: String? = null): String =
        if (tab.isNullOrBlank()) THREADS else "threads?$ARG_TAB=${Uri.encode(tab)}"

    /**
     * 深链（docs/spec.md §14）：`octopmobile://thread/{thread_id}`。
     *
     * 只带会话 id、不带 agent —— 通知是我们自己发的，说的就是当前 agent 下的会话。
     * scheme 与 host 必须和 `AndroidManifest.xml` 里 MainActivity 的 intent-filter 逐字一致，
     * 改这里就要同步改那里。
     */
    const val DEEP_LINK_SCHEME = "octopmobile"
    const val DEEP_LINK_HOST_THREAD = "thread"
    const val DEEP_LINK_HOST_TODAY = "today"

    /** 取出会话 id；不是本应用的深链（含 `http(s)` 打开本 Activity 的情况）返回 null，静默忽略。 */
    fun threadIdFromDeepLink(uri: Uri?): String? =
        uri?.takeIf { it.scheme == DEEP_LINK_SCHEME && it.host == DEEP_LINK_HOST_THREAD }
            ?.lastPathSegment
            ?.takeIf { it.isNotBlank() }

    /** 反向拼深链，用于通知点击回到对应会话（[com.octopmobile.app.service.TurnNotifications]）。 */
    fun threadDeepLink(threadId: String): Uri = Uri.Builder()
        .scheme(DEEP_LINK_SCHEME)
        .authority(DEEP_LINK_HOST_THREAD)
        .appendPath(threadId)
        .build()

    fun todayCardIdFromDeepLink(uri: Uri?): String? =
        uri?.takeIf { it.scheme == DEEP_LINK_SCHEME && it.host == DEEP_LINK_HOST_TODAY }
            ?.lastPathSegment
            ?.takeIf { it.isNotBlank() }

    fun todayDeepLink(cardId: String): Uri = Uri.Builder()
        .scheme(DEEP_LINK_SCHEME)
        .authority(DEEP_LINK_HOST_TODAY)
        .appendPath(cardId)
        .build()
}
