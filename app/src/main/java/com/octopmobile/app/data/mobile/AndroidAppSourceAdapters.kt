package com.octopmobile.app.data.mobile

import android.content.ContentResolver
import android.content.Context
import android.app.AlarmManager
import android.location.LocationManager
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Telephony
import android.app.usage.UsageStatsManager
import com.octopmobile.app.data.db.MobileSourceType
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AlarmTimerSourceAdapter(private val context: Context) {
    fun readNext(now: Long = System.currentTimeMillis()): List<MobileEventDraft> {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = manager.nextAlarmClock ?: return emptyList()
        if (alarm.triggerTime <= now) return emptyList()
        val creator = alarm.showIntent?.creatorPackage.orEmpty()
        val appLabel = creator.takeIf { it.isNotBlank() }?.let { packageName ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }
        val label = DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .format(Instant.ofEpochMilli(alarm.triggerTime).atZone(ZoneId.systemDefault()))
        return listOf(
            MobileEventDraft(
                id = "alarm-${sha256("${alarm.triggerTime}|$creator")}",
                sourceType = MobileSourceType.ANDROID_ALARM_TIMER,
                sourceRecordId = "alarm:${alarm.triggerTime}",
                eventType = "android.alarm_next",
                occurredAt = alarm.triggerTime,
                title = "闹钟和计时器",
                summary = listOfNotNull("下次闹钟：$label", appLabel).joinToString(" · "),
                payloadJson = buildJsonObject {
                    put("trigger_at", alarm.triggerTime)
                    if (appLabel != null) put("app", appLabel)
                    if (creator.isNotBlank()) put("package_name", creator)
                }.toString(),
                privacyClass = "private_user_content",
            ),
        )
    }

}

class UsageStatsSourceAdapter(private val context: Context) {
    fun readToday(now: Long = System.currentTimeMillis()): List<MobileEventDraft> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val totals = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
            .orEmpty()
            .groupBy { it.packageName }
            .mapValues { (_, stats) -> stats.sumOf { it.totalTimeInForeground } }
            .filterKeys { it != context.packageName && it != "android" }
            .filterValues { it >= 60_000L }
        if (totals.isEmpty()) return emptyList()

        val labels = totals.keys.associateWith { packageName ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }
        val sorted = totals.entries.sortedByDescending { it.value }
        val top = sorted.take(9)
        val summary = buildString {
            append("今天使用 ")
            append(formatDuration(totals.values.sum()))
            append("，最常用：")
            append(top.take(5).joinToString("、") { labels.getValue(it.key) })
        }
        val date = LocalDate.now(zone)
        val hourSlot = now / 3_600_000L
        return listOf(
            MobileEventDraft(
                id = "usage-${date}-$hourSlot",
                sourceType = MobileSourceType.ANDROID_USAGE_STATS,
                sourceRecordId = "usage:${date}:$hourSlot",
                eventType = "android.usage_daily",
                occurredAt = now,
                title = "安卓使用时间",
                summary = summary,
                payloadJson = buildJsonObject {
                    put("date", date.toString())
                    put("total_minutes", totals.values.sum() / 60_000L)
                    put(
                        "top_apps",
                        top.joinToString("、") { "${labels.getValue(it.key)} ${formatDuration(it.value)}" },
                    )
                    put("app_count", totals.size)
                }.toString(),
                privacyClass = "private_user_content",
            ),
        )
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000L
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (hours > 0) "${hours} 小时 ${remainder} 分" else "${remainder} 分钟"
    }
}

class ContactsSourceAdapter(private val resolver: ContentResolver) {
    fun readRecent(now: Long = System.currentTimeMillis()): List<MobileEventDraft> {
        val cutoff = now - 30L * 24 * 60 * 60 * 1000
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        return resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} >= ?",
            arrayOf(cutoff.toString()),
            "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_CONTACTS) {
                    val contactId = cursor.getString(0) ?: continue
                    val lookupKey = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val name = cursor.getString(2)?.trim().orEmpty().ifBlank { "联系人" }
                    val updatedAt = cursor.getLong(3).takeIf { it > 0 } ?: now
                    val phones = contactValues(
                        contactId,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    val emails = contactValues(
                        contactId,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    )
                    val fingerprint = "$lookupKey|$name|${phones.joinToString("|")}|" +
                        "${emails.joinToString("|")}|$updatedAt"
                    add(
                        MobileEventDraft(
                            id = "contacts-${sha256(fingerprint)}",
                            sourceType = MobileSourceType.ANDROID_CONTACTS,
                            sourceRecordId = lookupKey,
                            eventType = "android.contact",
                            occurredAt = updatedAt,
                            title = "通讯录",
                            summary = listOfNotNull(
                                name,
                                phones.firstOrNull(),
                                emails.firstOrNull(),
                            ).joinToString(" · "),
                            payloadJson = buildJsonObject {
                                put("name", name)
                                put("phones", phones.joinToString("、"))
                                put("emails", emails.joinToString("、"))
                                put("updated_at", updatedAt)
                            }.toString(),
                            privacyClass = "private_user_content",
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun contactValues(contactId: String, mimeType: String): List<String> =
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, mimeType),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < 5) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()

    private companion object {
        const val MAX_CONTACTS = 100
    }
}

class FileMetadataSourceAdapter {
    fun readRecent(now: Long = System.currentTimeMillis()): List<MobileEventDraft> {
        val cutoff = now - 7L * 24 * 60 * 60 * 1000
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        )
        val files = roots
            .filter { it.exists() && it.isDirectory }
            .flatMap { scan(it, cutoff, depth = 0) }
            .sortedByDescending { it.lastModified() }
            .take(MAX_FILES)
        if (files.isEmpty()) return emptyList()

        val names = files.take(12).joinToString("、") { it.name }
        return listOf(
            MobileEventDraft(
                id = "files-${now / 3_600_000L}",
                sourceType = MobileSourceType.ANDROID_FILES,
                sourceRecordId = "files:${now / 3_600_000L}",
                eventType = "android.files_recent",
                occurredAt = now,
                title = "安卓文件系统",
                summary = "最近 7 天有 ${files.size} 个文件更新：$names",
                payloadJson = buildJsonObject {
                    put("file_count", files.size)
                    put("files", files.joinToString("、") { it.name })
                    put("paths", files.joinToString("\n") { it.absolutePath })
                    put("total_bytes", files.sumOf { it.length() })
                }.toString(),
                privacyClass = "private_user_content",
            ),
        )
    }

    private fun scan(directory: File, cutoff: Long, depth: Int): List<File> {
        if (depth > MAX_DEPTH) return emptyList()
        val children = directory.listFiles()?.filterNot { it.isHidden } ?: return emptyList()
        return buildList {
            children.forEach { child ->
                if (child.isDirectory) {
                    addAll(scan(child, cutoff, depth + 1))
                } else if (child.lastModified() >= cutoff) {
                    add(child)
                }
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 2
        const val MAX_FILES = 100
    }
}

class LocationSourceAdapter(private val context: Context) {
    fun readCurrent(now: Long = System.currentTimeMillis()): List<MobileEventDraft> {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val location = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time } ?: return emptyList()
        if (now - location.time > MAX_LOCATION_AGE_MS) return emptyList()

        val summary = buildString {
            append("%.6f, %.6f".format(location.latitude, location.longitude))
            if (location.hasAccuracy()) append(" · 精度约 ${location.accuracy.toInt()} 米")
        }
        return listOf(
            MobileEventDraft(
                id = "location-${sha256("${location.latitude}|${location.longitude}|${location.time}")}",
                sourceType = MobileSourceType.ANDROID_LOCATION,
                sourceRecordId = "location:${location.time}",
                eventType = "android.location_current",
                occurredAt = location.time,
                title = "安卓位置",
                summary = summary,
                payloadJson = buildJsonObject {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy_meters", if (location.hasAccuracy()) location.accuracy else null)
                    put("provider", location.provider.orEmpty())
                    put("observed_at", location.time)
                }.toString(),
                privacyClass = "private_location",
            ),
        )
    }

    private companion object {
        const val MAX_LOCATION_AGE_MS = 24L * 60 * 60 * 1000
    }
}

class SmsSourceAdapter(private val resolver: ContentResolver) {
    fun readRecent(now: Long = System.currentTimeMillis()): List<MobileEventDraft> {
        val cutoff = now - 7L * 24 * 60 * 60 * 1000
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )
        return resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(cutoff.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_MESSAGES) {
                    val address = cursor.getString(1)?.trim().orEmpty().ifBlank { "未知号码" }
                    val body = cursor.getString(2).orEmpty().trim()
                    val date = cursor.getLong(3).takeIf { it > 0 } ?: now
                    val type = cursor.getInt(4)
                    val fingerprint = "$address|$date|${sha256(body)}"
                    add(
                        MobileEventDraft(
                            id = "sms-${sha256(fingerprint)}",
                            sourceType = MobileSourceType.ANDROID_SMS,
                            sourceRecordId = "$address:$date",
                            eventType = "android.sms",
                            occurredAt = date,
                            title = "短信",
                            summary = "$address · ${body.take(140)}",
                            payloadJson = buildJsonObject {
                                put("address", address)
                                put("body", body.take(500))
                                put("received_at", date)
                                put("message_type", type)
                            }.toString(),
                            privacyClass = "private_user_content",
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private companion object {
        const val MAX_MESSAGES = 50
    }
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
