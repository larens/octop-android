package com.octopmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.octopmobile.app.R
import com.octopmobile.app.ui.MainActivity
import com.octopmobile.app.ui.Routes

/**
 * 长任务进度的通知（docs/spec.md §14）。
 *
 * 两条路线：Android 16 起用 `ProgressStyle`，更早的版本退回普通的进行中通知。两者都靠
 * 「前台服务 + `setOngoing(true)` + 不定长进度」表达「还在跑」。
 *
 * 注意实现边界（已对 android-36 平台 jar 复核）：这个版本的公开 API 里**没有**
 * `Notification.Builder.setRequestPromotedOngoing`，也**没有** `POST_PROMOTED_NOTIFICATIONS`
 * 权限（框架里只有内部的 `FLAG_PROMOTED_ONGOING`）。所以这里不追求把通知提升成状态栏胶囊，
 * 只要它在通知栏里如实挂着进度即可。
 */
object TurnNotifications {

    const val CHANNEL_ID = "turn_progress"
    const val NOTIFICATION_ID = 1001

    /**
     * 通知点击的 PendingIntent 是「同一个会话复用同一个」，所以 requestCode 固定。
     * `FLAG_UPDATE_CURRENT` 保证换会话时把旧的 data 换掉，而不是留着第一次的那个。
     */
    private const val REQUEST_CODE = 2001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            // LOW：这是一条状态提示，不是闹钟。静音、不弹横幅，只在通知栏里挂着。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, title: String, text: String, threadId: String): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context, threadId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 用户一按「停止」，服务该当场退场，而不是等下一轮调度。
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        // 两条路线都要给 Builder 一个不定长进度：ProgressStyle 在 styledByProgress=true 时
        // 是读 Builder 的进度值来画的，只挂 style 不设进度，进度条不会出现。
        builder.setProgress(0, 0, true)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressIndeterminate(true),
            )
        }
        return builder.build()
    }

    /**
     * 点通知回到那条会话。走自定义 scheme 深链而不是直接构造路由字符串 ——
     * MainActivity 处理深链那条路径已经有登录态检查与 agent 解析（spec §14）。
     */
    private fun contentIntent(context: Context, threadId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Routes.threadDeepLink(threadId)
            // 已经在栈里时把现有实例拉到前台，不要再叠一个（MainActivity 是 singleTask）。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
