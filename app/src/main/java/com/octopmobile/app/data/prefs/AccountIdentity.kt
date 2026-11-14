package com.octopmobile.app.data.prefs

/**
 * 账号边界。
 *
 * 同一台设备换账号时，必须丢弃上一个账号的 agent 选择和本地镜像；否则 A 的会话、
 * Today 卡片和健康数据会在 B 登录后短暂可见。
 */
data class AccountIdentity(
    val host: String?,
    val userId: Long?,
    val username: String?,
) {
    fun differsFrom(host: String, userId: Long?, username: String?): Boolean {
        if (this.host.isNullOrBlank()) return false
        if (this.host != host) return true
        if (this.userId != null && userId != null) return this.userId != userId
        val previous = this.username?.trim().orEmpty()
        val next = username?.trim().orEmpty()
        return previous.isNotEmpty() && next.isNotEmpty() && previous != next
    }
}
