package com.octopmobile.app.ui

import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 从系统入口（App Shortcuts、分享）带进来的一次性内容（docs/spec.md §14）。
 *
 * 这些入口的终点是「某个会话」，但从入口到会话之间隔着「进 App → 建会话 → 导航」三步，
 * 中间还有登录态检查，没法只靠 Intent extra 一路传下去，所以先在内存里放一下。
 */
data class Handoff(
    /** 分享进来的文字（`ACTION_SEND` 的 `EXTRA_TEXT`）。 */
    val text: String? = null,
    /** 分享进来的附件。取不到读取权限时会被逐个跳过（见 `ChatViewModel.share`）。 */
    val uris: List<Uri> = emptyList(),
    /** [AppActions] 之一：建完会话之后还要顺手做什么。 */
    val action: String? = null,
)

object AppActions {
    const val NEW_THREAD = "com.octopmobile.app.action.NEW_THREAD"
    const val CAMERA = "com.octopmobile.app.action.CAMERA"

    /** 分享入口没有自己的 action 常量（用的是系统的 `ACTION_SEND`），这里给它一个内部标识。 */
    const val SHARE = "com.octopmobile.app.action.SHARE"

    /** 任务进度通知挂靠的快捷方式 id（`res/xml/shortcuts.xml`，Android 16 Live Updates 用）。 */
    const val SHORTCUT_NEW_THREAD = "new_thread"

    /** 只认自家 action；`MAIN` / `VIEW` / `SEND` 都不算。 */
    fun from(intentAction: String?): String? = intentAction?.takeIf {
        it == NEW_THREAD || it == CAMERA
    }
}

/**
 * 一个「放一次、取一次」的槽位。
 *
 * 加 TTL 是因为它跟界面走：用户点了快捷方式但当时停在登录页（没人来取），
 * 过一会儿登录完才进列表时，那次点击已经过期了，不该突然建出一个会话。
 */
private class OneShot(private val ttlMs: Long = 60_000L) {

    private var armedAt = 0L
    private val state = MutableStateFlow<Handoff?>(null)

    val pending: StateFlow<Handoff?> = state.asStateFlow()

    fun arm(handoff: Handoff) {
        armedAt = SystemClock.elapsedRealtime()
        state.value = handoff
    }

    /** 取走并清空。没有待处理内容或已经超时都返回 null。 */
    fun take(): Handoff? {
        val current = state.value ?: return null
        state.value = null
        return current.takeIf { SystemClock.elapsedRealtime() - armedAt <= ttlMs }
    }
}

/** 工作台「会话」栏这一级要消费的交接：Shortcuts 与分享都由它起始（新会话页消费）。 */
object PendingHandoff {
    private val slot = OneShot()

    val pending: StateFlow<Handoff?> = slot.pending

    fun arm(handoff: Handoff) = slot.arm(handoff)

    fun take(): Handoff? = slot.take()
}

/**
 * 会话页这一级要消费的交接（`ChatScreen` 消费）。
 *
 * 单独一个槽位而不是复用 [PendingHandoff]：列表页建完会话、把动作交下去之后自己还在返回栈里，
 * 若共用同一个状态流，它会被自己刚写进去的值再触发一次，凭空多建一个会话。
 */
object PendingChatStart {
    private val slot = OneShot()

    val pending: StateFlow<Handoff?> = slot.pending

    fun arm(handoff: Handoff) = slot.arm(handoff)

    fun take(): Handoff? = slot.take()
}

/**
 * 程序自动新建的会话。只要用户真的发过消息就永久保留；没有互动就随会话页退出自动删掉。
 * 用进程级集合记而不是「最近一条」：会话页还可能再从抽屉里开出一条新会话，
 * 先前那条空会话在离开时同样要能删掉（spec: 新会话无互动则销毁）。
 */
object AutoCreatedConversation {
    private val created = ConcurrentHashMap.newKeySet<String>()

    fun mark(value: String) {
        created.add(value)
    }

    fun isTracked(value: String): Boolean = value in created

    fun consume(value: String) {
        created.remove(value)
    }
}
