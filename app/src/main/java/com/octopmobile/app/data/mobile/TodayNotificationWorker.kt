package com.octopmobile.app.data.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.octopmobile.app.OctopApp
import com.octopmobile.app.data.db.TodayCardLifecycle
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.ws.TodayCardPush
import com.octopmobile.app.service.TodayNotificationRules
import com.octopmobile.app.service.TodayNotifications
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 后台轮询 Today 卡片，补上前台 WebSocket 断开时的手机通知。
 *
 * 只提醒行业信号、新闻简报和晚间用量，避免把日程、健康等已有独立提醒的卡片重复弹一遍。
 */
class TodayNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OctopApp).container
        val settings = container.settings.current()
        if (!TodayNotificationRules.shouldNotify(
                "p1",
                settings.notificationsEnabled,
                settings.scheduledTaskNotifications,
            )
        ) {
            return Result.success()
        }
        if (LocalTime.now().hour !in NOTIFY_HOURS) return Result.success()

        val host = container.prefs.currentHost() ?: return Result.success()
        val agentId = container.prefs.selectedAgentId.first() ?: return Result.success()
        val repository = container.todayRepository(host, agentId)
        when (val refreshed = repository.refresh()) {
            is ApiResult.Failure -> {
                val networkFailure = refreshed.error is com.octopmobile.app.data.remote.ApiError.Network
                return if (networkFailure) Result.retry() else Result.success()
            }
            is ApiResult.Ok -> Unit
        }

        val scanPrefs = applicationContext.getSharedPreferences(
            SCAN_PREFS,
            Context.MODE_PRIVATE,
        )
        val now = System.currentTimeMillis()
        val storedScanAt = scanPrefs.getLong(KEY_LAST_SCAN_AT, 0L)
        val lastScanAt = storedScanAt.takeIf { it > 0L } ?: (now - FIRST_SCAN_LOOKBACK_MS)

        repository.observeCards().first()
            .asSequence()
            .filter { it.lifecycle == TodayCardLifecycle.UNREAD }
            .filter { it.updatedAt > lastScanAt - SCAN_OVERLAP_MS }
            .filter { it.kind in NOTIFY_KINDS || (it.kind == "briefing" && it.title.contains("新闻")) }
            .sortedBy { it.updatedAt }
            .take(MAX_NOTIFICATIONS_PER_SCAN)
            .forEach { card ->
                TodayNotifications.show(
                    context = applicationContext,
                    push = TodayCardPush(
                        agentId = agentId,
                        cardId = card.id,
                        priority = "p1",
                        title = card.title,
                        text = com.octopmobile.app.ui.workbench.todayCardSummaryPreview(card.summary),
                        state = card.lifecycle,
                    ),
                    version = card.updatedAt,
                )
            }
        scanPrefs.edit().putLong(KEY_LAST_SCAN_AT, now).apply()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "today-notification-poller"
        private const val SCAN_PREFS = "today_notification_scan"
        private const val KEY_LAST_SCAN_AT = "last_scan_at"
        private const val FIRST_SCAN_LOOKBACK_MS = 30 * 60 * 1000L
        private const val SCAN_OVERLAP_MS = 5 * 60 * 1000L
        private const val MAX_NOTIFICATIONS_PER_SCAN = 3
        private val NOTIFY_HOURS = 7..22
        private val NOTIFY_KINDS = setOf("signal", "news", "usage")

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TodayNotificationWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
