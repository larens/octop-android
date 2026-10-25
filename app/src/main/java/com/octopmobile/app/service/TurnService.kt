package com.octopmobile.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.octopmobile.app.R

/**
 * 长任务前台服务（docs/spec.md §14）。
 *
 * 它只做一件事：让进程在后台活着，并挂一条进行中通知。**不持有任何连接** ——
 * WebSocket 与回合状态全在 `ChatRepository`，服务多存一份就多一处要同步的状态。
 * 所以对外只有 [start] / [stop] 两个动作，由会话页跟着 `turnActive` 驱动。
 */
class TurnService : Service() {

    private var threadId: String = ""
    private var title: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        threadId = intent.getStringExtra(EXTRA_THREAD_ID).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE)

        // startForeground 是会抛的（后台启动限制、类型没声明、通知被禁等）。抛了就当作没起：
        // 它本来只是「别被系统杀掉」的尽力而为，不能反过来把聊天弄崩。
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                TurnNotifications.NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.isSuccess
        if (!started) stopSelf()

        // 我们进程被杀后重启一个空服务没有意义 —— 连接是随 ViewModel 走的，服务本身不认路。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification() = TurnNotifications.build(
        context = this,
        title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name),
        text = getString(R.string.notif_turn_text),
        threadId = threadId,
    )

    companion object {
        private const val ACTION_START = "com.octopmobile.app.turn.START"
        private const val EXTRA_THREAD_ID = "threadId"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, threadId: String, title: String?) {
            TurnNotifications.ensureChannel(context)
            val intent = Intent(context, TurnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_TITLE, title)
            }
            // Android 12+ 不允许后台启动前台服务；那里静默放弃，用户就在前台，本来也不需要它。
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TurnService::class.java)) }
        }
    }
}
