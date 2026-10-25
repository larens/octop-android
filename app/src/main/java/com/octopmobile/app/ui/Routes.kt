package com.octopmobile.app.ui

/**
 * 导航路由表（docs/spec.md §6 的 `ui/` 结构）。
 *
 * 只有 [CHAT_PATTERN] 带路径参数：会话列表的 agent 过滤是页面内状态、不进路由，
 * 这样 [THREADS] 才能直接当 `startDestination` 用（navigation-compose 要求
 * startDestination 与注册的 route 字符串完全一致，带可空参数的 pattern 不满足）。
 */
object Routes {

    const val CONNECT = "connect"
    const val LOGIN = "login"
    const val THREADS = "threads"
    const val SETTINGS = "settings"

    const val ARG_AGENT_ID = "agentId"
    const val ARG_THREAD_ID = "threadId"

    const val CHAT_PATTERN = "chat/{$ARG_AGENT_ID}/{$ARG_THREAD_ID}"

    /** 会话内的 `agent_id` 是业务字符串标识，不是服务端自增主键 `id`（见 docs/protocol.md §4）。 */
    fun chat(agentId: String, threadId: String): String = "chat/$agentId/$threadId"
}
