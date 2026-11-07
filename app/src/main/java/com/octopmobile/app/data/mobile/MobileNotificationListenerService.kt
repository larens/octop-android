package com.octopmobile.app.data.mobile

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.octopmobile.app.OctopApp
import com.octopmobile.app.data.db.MobileSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 只把用户已开启「通知」连接器后的可清除通知写入本地加密队列。
 *
 * 不保留常驻通知（例如正在播放的媒体），避免把状态栏控制项误当成信息线索。
 */
class MobileNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        val fingerprint = "${sbn.key}|${sbn.postTime}|$title|$text"
        val draft = MobileEventDraft(
            id = "notification-${sha256(fingerprint)}",
            sourceType = MobileSourceType.ANDROID_NOTIFICATIONS,
            sourceRecordId = sbn.key,
            eventType = "android.notification",
            occurredAt = sbn.postTime,
            title = "通知 · $appLabel",
            summary = listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ").take(220),
            payloadJson = buildJsonObject {
                put("app", appLabel)
                put("package_name", sbn.packageName)
                put("title", title.take(200))
                put("text", text.take(500))
                put("posted_at", sbn.postTime)
            }.toString(),
            privacyClass = "private_user_content",
        )
        val container = (applicationContext as OctopApp).container
        scope.launch {
            runCatching { container.mobileDataRepository().saveCaptured(draft) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal fun Context.hasNotificationListenerAccess(): Boolean =
    AndroidAppConnectorAccess.hasNotificationAccess(this)
