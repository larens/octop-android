package com.octopmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.octopmobile.app.R
import com.octopmobile.app.data.ws.TodayCardPush
import com.octopmobile.app.ui.MainActivity
import com.octopmobile.app.ui.Routes

/**
 * 重要 Today 变动在手机上提示。
 *
 * 服务端已经把重要度算好了：`p0` 立刻提醒、`p1` 进汇总窗口、`p2` 只进 Today。
 * 客户端只负责两件事：把 `p0` 放高优先级通道、把 `p1` 放普通通道；其余一律不弹。
 *
 * 边界：dashboard push 是前台连接（[com.octopmobile.app.ui.workbench.TodayPushViewModel]），
 * App 退到后台时这里收不到消息。真正的后台触达由服务端给本人发飞书私聊兜底。
 */
object TodayNotifications {
    const val CHANNEL_IMPORTANT = "today_important"
    const val CHANNEL_BRIEFING = "today_briefing"

    fun show(context: Context, push: TodayCardPush): Boolean {
        val channel = TodayNotificationRules.channelFor(push.priority) ?: return false
        ensureChannel(context, channel)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val title = push.title.ifBlank { context.getString(R.string.notif_today_default_title) }
        val body = push.text.ifBlank { push.title }
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" · ")
            .take(240)
            .ifBlank { context.getString(R.string.notif_today_default_body) }
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context, push.cardId))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        return runCatching {
            manager.notify(notificationId(push), notification)
            true
        }.getOrDefault(false)
    }

    private fun notificationId(push: TodayCardPush): Int = "${push.agentId}:${push.cardId}".hashCode()

    private fun ensureChannel(context: Context, channelId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return
        val important = channelId == CHANNEL_IMPORTANT
        val channel = NotificationChannel(
            channelId,
            context.getString(
                if (important) R.string.notif_today_important_channel_name
                else R.string.notif_today_briefing_channel_name,
            ),
            if (important) NotificationManager.IMPORTANCE_HIGH
            else NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(
                if (important) R.string.notif_today_important_channel_desc
                else R.string.notif_today_briefing_channel_desc,
            )
            setShowBadge(important)
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context, cardId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Routes.todayDeepLink(cardId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            cardId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 纯规则，便于单测：哪些 push 该弹、弹哪条通道。 */
object TodayNotificationRules {
    fun channelFor(priority: String): String? = when (priority.trim().lowercase()) {
        "p0" -> TodayNotifications.CHANNEL_IMPORTANT
        "p1" -> TodayNotifications.CHANNEL_BRIEFING
        else -> null
    }

    fun shouldNotify(
        priority: String,
        notificationsEnabled: Boolean,
        scheduledTaskNotifications: Boolean,
    ): Boolean = notificationsEnabled && scheduledTaskNotifications && channelFor(priority) != null

    /** 同一个卡片 6 小时内只弹一次，避免重连或重复帧把同一条提醒刷成多条。 */
    fun shouldDedupe(
        lastShownAt: Long?,
        now: Long,
        windowMillis: Long = DEDUPE_WINDOW_MILLIS,
    ): Boolean = lastShownAt != null && now - lastShownAt < windowMillis

    const val DEDUPE_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
}
