package com.octopmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.octopmobile.app.R
import com.octopmobile.app.data.settings.SettingsPreferences
import com.octopmobile.app.data.ws.TodayCardPush
import com.octopmobile.app.ui.MainActivity
import com.octopmobile.app.ui.Routes
import java.time.LocalDate
import java.time.LocalTime

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

    suspend fun show(context: Context, push: TodayCardPush, version: Long? = null): Boolean {
        val channel = TodayNotificationRules.channelFor(push.priority) ?: return false
        val now = System.currentTimeMillis()
        val settings = SettingsPreferences(context).current()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val shownToday = TodayNotificationRules.dailyCount(
            storedDate = prefs.getString(KEY_DAILY_DATE, null),
            today = today,
            storedCount = prefs.getInt(KEY_DAILY_COUNT, 0),
        )
        if (!TodayNotificationRules.shouldPost(
                channel = channel,
                quietHoursEnabled = settings.quietHoursEnabled,
                now = LocalTime.now(),
                shownToday = shownToday,
            )
        ) {
            return false
        }
        val dedupeKey = version?.let { "${push.cardId}@$it" } ?: push.cardId
        val lastShownAt = prefs.getLong(dedupeKey, 0L)
        if (TodayNotificationRules.shouldDedupe(lastShownAt, now)) return false
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
        val shown = runCatching {
            manager.notify(notificationId(push), notification)
            true
        }.getOrDefault(false)
        if (shown) {
            prefs.edit()
                .putLong(dedupeKey, now)
                .putString(KEY_DAILY_DATE, today)
                .putInt(KEY_DAILY_COUNT, shownToday + 1)
                .apply()
        }
        return shown
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

    private const val PREFS_NAME = "today_notification_history"
    private const val KEY_DAILY_DATE = "daily_date"
    private const val KEY_DAILY_COUNT = "daily_count"
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

    /** 安静时段只约束简报；p0 是紧急提醒，允许穿透。 */
    fun shouldRespectQuietHours(channel: String?, quietHoursEnabled: Boolean): Boolean =
        quietHoursEnabled && channel == TodayNotifications.CHANNEL_BRIEFING

    fun isQuietHours(
        now: LocalTime,
        start: LocalTime = QUIET_START,
        end: LocalTime = QUIET_END,
    ): Boolean = if (start <= end) {
        now >= start && now < end
    } else {
        now >= start || now < end
    }

    /** 每日上限只约束简报，避免汇总类提醒堆积。 */
    fun canReachDailyLimit(shownToday: Int, limit: Int = DAILY_LIMIT): Boolean = shownToday < limit

    fun shouldPost(
        channel: String?,
        quietHoursEnabled: Boolean,
        now: LocalTime,
        shownToday: Int,
        dailyLimit: Int = DAILY_LIMIT,
    ): Boolean {
        if (channel == null) return false
        if (shouldRespectQuietHours(channel, quietHoursEnabled) && isQuietHours(now)) return false
        if (channel == TodayNotifications.CHANNEL_BRIEFING && !canReachDailyLimit(shownToday, dailyLimit)) return false
        return true
    }

    fun dailyCount(storedDate: String?, today: String, storedCount: Int): Int =
        if (storedDate == today) storedCount.coerceAtLeast(0) else 0

    /** 同一个卡片 6 小时内只弹一次，避免重连或重复帧把同一条提醒刷成多条。 */
    fun shouldDedupe(
        lastShownAt: Long?,
        now: Long,
        windowMillis: Long = DEDUPE_WINDOW_MILLIS,
    ): Boolean = lastShownAt != null && now - lastShownAt < windowMillis

    const val DEDUPE_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    const val DAILY_LIMIT = 5
    private val QUIET_START: LocalTime = LocalTime.of(22, 0)
    private val QUIET_END: LocalTime = LocalTime.of(7, 30)
}
