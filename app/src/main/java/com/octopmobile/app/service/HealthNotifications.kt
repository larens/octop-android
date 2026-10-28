package com.octopmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.octopmobile.app.R
import com.octopmobile.app.ui.MainActivity
import com.octopmobile.app.ui.Routes

/** Local morning/evening reminders backed by the server-generated Today health card. */
object HealthNotifications {
    private const val CHANNEL_ID = "health_reminders"
    private const val MORNING_NOTIFICATION_ID = 2101
    private const val EVENING_NOTIFICATION_ID = 2102

    fun show(
        context: Context,
        window: String,
        cardId: String,
        title: String,
        summary: String,
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notificationId = if (window == WINDOW_MORNING) {
            MORNING_NOTIFICATION_ID
        } else {
            EVENING_NOTIFICATION_ID
        }
        val body = notificationBody(summary)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context, cardId))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_health_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_health_channel_desc)
            setShowBadge(true)
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

    private fun notificationBody(summary: String): String =
        summary.lineSequence()
            .map(String::trim)
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("【") &&
                    !it.startsWith("{")
            }
            .joinToString(" · ")
            .take(320)
            .ifBlank { "打开 Today 查看今天的健康总结。" }

    const val WINDOW_MORNING = "morning"
    const val WINDOW_EVENING = "evening"
}
