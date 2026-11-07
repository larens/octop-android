package com.octopmobile.app.data.mobile

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.octopmobile.app.data.db.MobileSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 系统连接器的权限与采集入口。
 *
 * 这里只读取用户在「连接应用」里逐项开启的来源。特殊权限（使用情况、通知使用权、
 * 所有文件访问）只负责打开系统设置，不替用户做授权决定。
 */
object AndroidAppConnectorAccess {
    fun runtimePermissions(sourceType: String): List<String> = when (sourceType) {
        MobileSourceType.SYSTEM_CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        MobileSourceType.ANDROID_CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        MobileSourceType.ANDROID_LOCATION -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        MobileSourceType.ANDROID_SMS -> listOf(Manifest.permission.READ_SMS)
        MobileSourceType.ANDROID_FILES ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                emptyList()
            }
        else -> emptyList()
    }

    fun hasAccess(context: Context, sourceType: String): Boolean = when (sourceType) {
        MobileSourceType.ANDROID_ALARM_TIMER -> true
        MobileSourceType.ANDROID_USAGE_STATS -> hasUsageAccess(context)
        MobileSourceType.ANDROID_NOTIFICATIONS -> hasNotificationAccess(context)
        MobileSourceType.ANDROID_FILES ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                runtimeAccessGranted(context, sourceType)
            }
        else -> runtimeAccessGranted(context, sourceType)
    }

    fun runtimeAccessGranted(
        context: Context,
        sourceType: String,
        results: Map<String, Boolean>? = null,
    ): Boolean {
        val permissions = runtimePermissions(sourceType)
        if (permissions.isEmpty()) return false
        fun granted(permission: String): Boolean = results?.get(permission)
            ?: (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
        return if (sourceType == MobileSourceType.ANDROID_LOCATION) {
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                granted(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.all(::granted)
        }
    }

    fun specialAccessSettings(context: Context, sourceType: String): Intent? = when (sourceType) {
        MobileSourceType.ANDROID_USAGE_STATS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        MobileSourceType.ANDROID_NOTIFICATIONS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        MobileSourceType.ANDROID_FILES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            null
        }
        else -> null
    }

    suspend fun collect(context: Context, sourceType: String): List<MobileEventDraft> =
        withContext(Dispatchers.IO) {
            if (!hasAccess(context, sourceType)) {
                error("需要系统权限")
            }
            when (sourceType) {
                MobileSourceType.SYSTEM_CALENDAR ->
                    CalendarSourceAdapter(context.contentResolver).readWindow()
                MobileSourceType.ANDROID_ALARM_TIMER ->
                    AlarmTimerSourceAdapter(context).readNext()
                MobileSourceType.ANDROID_USAGE_STATS ->
                    UsageStatsSourceAdapter(context).readToday()
                MobileSourceType.ANDROID_CONTACTS ->
                    ContactsSourceAdapter(context.contentResolver).readRecent()
                MobileSourceType.ANDROID_FILES ->
                    FileMetadataSourceAdapter().readRecent()
                MobileSourceType.ANDROID_LOCATION ->
                    LocationSourceAdapter(context).readCurrent()
                MobileSourceType.ANDROID_SMS ->
                    SmsSourceAdapter(context.contentResolver).readRecent()
                MobileSourceType.ANDROID_NOTIFICATIONS,
                -> emptyList()
                else -> emptyList()
            }
        }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
