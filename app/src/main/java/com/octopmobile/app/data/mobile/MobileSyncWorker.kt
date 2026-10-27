package com.octopmobile.app.data.mobile

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.octopmobile.app.OctopApp
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.db.MobileSourceType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class MobileSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OctopApp).container
        val host = container.prefs.currentHost() ?: return Result.success()
        val agentId = container.prefs.selectedAgentId.first() ?: return Result.success()
        val repository = container.mobileDataRepository()
        runCatching {
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                repository.collect(MobileSourceType.SYSTEM_CALENDAR) {
                    CalendarSourceAdapter(applicationContext.contentResolver).readWindow()
                }
            }
            if (HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE) {
                val client = HealthConnectClient.getOrCreate(applicationContext)
                val granted = client.permissionController.getGrantedPermissions()
                repository.collect(MobileSourceType.HEALTH_CONNECT) {
                    HealthConnectSourceAdapter(client).readRecent(granted)
                }
            }
        }
        return when (val result =
            repository.uploadPending(
                container.api(host),
                agentId,
                container.mobileDeviceId(),
            )
        ) {
            is ApiResult.Ok -> Result.success()
            is ApiResult.Failure -> when (val error = result.error) {
                is ApiError.Network -> Result.retry()
                is ApiError.Http -> if (error.status >= 500 || error.status == 429) Result.retry() else Result.success()
                is ApiError.Parse -> Result.success()
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK = "mobile-data-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MobileSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
