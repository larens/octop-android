package com.octopmobile.app.data.mobile

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.octopmobile.app.OctopApp
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.db.MobileSourceType
import com.octopmobile.app.service.HealthNotifications
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class MobileSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OctopApp).container
        val host = container.prefs.currentHost()
        val agentId = container.prefs.selectedAgentId.first()
        if (host == null || agentId == null) return Result.success()
        val repository = container.mobileDataRepository()
        repository.ensureSources()
        val reminderWindow = inputData.getString(HEALTH_WINDOW_KEY)
        val calendarEnabled = repository.observeSources().first().any {
            it.sourceType == MobileSourceType.SYSTEM_CALENDAR && it.enabled
        }
        runCatching {
            if (
                calendarEnabled &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                repository.collect(MobileSourceType.SYSTEM_CALENDAR) {
                    CalendarSourceAdapter(applicationContext.contentResolver).readWindow()
                }
            }
        }
        val uploadResult = repository.uploadPending(
            container.api(host),
            agentId,
            container.mobileDeviceId(),
        )
        if (uploadResult is ApiResult.Ok && reminderWindow != null) {
            runCatching { notifyHealthCard(container, host, agentId, reminderWindow) }
        }
        val result = when (uploadResult) {
            is ApiResult.Ok -> Result.success()
            is ApiResult.Failure -> when (val error = uploadResult.error) {
                is ApiError.Network -> Result.retry()
                is ApiError.Http -> if (error.status >= 500 || error.status == 429) Result.retry() else Result.success()
                is ApiError.Parse -> Result.success()
            }
        }
        return result
    }

    private suspend fun notifyHealthCard(
        container: com.octopmobile.app.data.AppContainer,
        host: String,
        agentId: String,
        window: String,
    ) {
        val hour = LocalTime.now().hour
        when (window) {
            HealthNotifications.WINDOW_MORNING -> if (hour !in 5..11) return
            HealthNotifications.WINDOW_EVENING -> if (hour < 18) return
            else -> return
        }
        val today = LocalDate.now()
        val prefs = applicationContext.getSharedPreferences("mobile_health_reminders", Context.MODE_PRIVATE)
        val notifiedKey = "last_${window}_date"
        if (prefs.getString(notifiedKey, null) == today.toString()) return

        val todayRepository = container.todayRepository(host, agentId)
        if (todayRepository.refresh() !is ApiResult.Ok) return
        val zone = java.time.ZoneId.systemDefault()
        val card = todayRepository.observeCards().first()
            .asSequence()
            .filter { it.kind == "health" }
            .filter {
                java.time.Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() == today
            }
            .filter {
                val heading = if (window == HealthNotifications.WINDOW_MORNING) {
                    "【睡眠概览】"
                } else {
                    "【今日概览】"
                }
                heading in it.summary
            }
            .maxByOrNull { it.updatedAt }
            ?: return

        HealthNotifications.show(
            context = applicationContext,
            window = window,
            cardId = card.id,
            title = card.title,
            summary = card.summary,
        )
        prefs.edit().putString(notifiedKey, today.toString()).apply()
    }

    companion object {
        private const val UNIQUE_WORK = "mobile-data-sync"
        private const val MORNING_WORK = "mobile-health-morning"
        private const val EVENING_WORK = "mobile-health-evening"
        private const val MORNING_HOUR = 7
        // Let the server's :00/:30 Xiaomi cloud sync publish the latest card first.
        private const val MORNING_MINUTE = 35
        private const val EVENING_HOUR = 19
        private const val EVENING_MINUTE = 35
        private const val REMINDER_INTERVAL_HOURS = 24L
        internal const val HEALTH_WINDOW_KEY = "health_window"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MobileSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            scheduleReminder(context, HealthNotifications.WINDOW_MORNING)
            scheduleReminder(context, HealthNotifications.WINDOW_EVENING)
        }

        private fun scheduleReminder(context: Context, window: String) {
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.now(zone)
            val targetTime = when (window) {
                HealthNotifications.WINDOW_MORNING ->
                    LocalTime.of(MORNING_HOUR, MORNING_MINUTE)
                else -> LocalTime.of(EVENING_HOUR, EVENING_MINUTE)
            }
            var target = LocalDateTime.of(now.toLocalDate(), targetTime)
            if (!target.isAfter(now)) target = target.plusDays(1)
            val request = PeriodicWorkRequestBuilder<MobileSyncWorker>(
                REMINDER_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setInputData(workDataOf(HEALTH_WINDOW_KEY to window))
                .setInitialDelay(Duration.between(now, target).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            val name = if (window == HealthNotifications.WINDOW_MORNING) MORNING_WORK else EVENING_WORK
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                name,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }
    }
}
