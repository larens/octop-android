package com.octopmobile.app.data.mobile

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CalendarContract
import com.octopmobile.app.data.db.MobileSourceType
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class CalendarMeetingSignals(
    val isMeeting: Boolean,
    val isInvitation: Boolean,
)

internal fun calendarMeetingSignals(
    organizer: String?,
    isOrganizer: Boolean?,
    hasAttendeeData: Boolean?,
): CalendarMeetingSignals {
    val hasEmailOrganizer = organizer?.contains("@") == true
    val isMeeting = hasAttendeeData == true || hasEmailOrganizer
    return CalendarMeetingSignals(
        isMeeting = isMeeting,
        isInvitation = isMeeting && isOrganizer == false && hasEmailOrganizer,
    )
}

class CalendarSourceAdapter(private val resolver: ContentResolver) {
    fun readWindow(now: Long = System.currentTimeMillis()): List<MobileEventDraft> {
        val start = now - PAST_WINDOW_MS
        val end = now + FUTURE_WINDOW_MS
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ORGANIZER,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            "eventStatus",
            CalendarContract.Instances.IS_ORGANIZER,
            CalendarContract.Instances.HAS_ATTENDEE_DATA,
            CalendarContract.Instances.UID_2445,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString())
            .appendPath(end.toString())
            .build()
        return resolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val recordId = cursor.getLong(0).toString()
                    val calendarId = cursor.getLong(1).toString()
                    val title = cursor.getString(2).orEmpty().ifBlank { "日程" }
                    val startsAt = cursor.getLong(3)
                    val endsAt = cursor.getLong(4)
                    val location = cursor.getString(5)
                    val organizer = cursor.getString(6)?.trim()?.takeIf { it.isNotBlank() }
                    val selfAttendeeStatus = cursor.getNullableInt(7)
                    val eventStatus = cursor.getNullableInt(8)
                    val rawIsOrganizer = cursor.getNullableInt(9)
                    val rawHasAttendeeData = cursor.getNullableInt(10)
                    val calendarUid = cursor.getString(11)?.trim()?.takeIf { it.isNotBlank() }
                    val signals = calendarMeetingSignals(
                        organizer = organizer,
                        isOrganizer = rawIsOrganizer?.let { it == 1 },
                        hasAttendeeData = rawHasAttendeeData?.let { it == 1 },
                    )
                    val meetingFingerprint =
                        "$organizer|$selfAttendeeStatus|$eventStatus|$rawIsOrganizer|$rawHasAttendeeData"
                    val fingerprint =
                        "$calendarId|$recordId|$startsAt|$endsAt|$title|$location|$meetingFingerprint"
                    add(
                        MobileEventDraft(
                            id = "calendar-${sha256(fingerprint)}",
                            sourceType = MobileSourceType.SYSTEM_CALENDAR,
                            sourceRecordId = "$calendarId:$recordId:$startsAt",
                            eventType = "calendar.event",
                            occurredAt = startsAt,
                            title = "系统日历",
                            summary = title,
                            payloadJson = buildJsonObject {
                                put("title", title)
                                put("starts_at", startsAt)
                                put("ends_at", endsAt)
                                if (!location.isNullOrBlank()) put("location", location)
                                put("calendar_id", calendarId)
                                if (calendarUid != null) put("calendar_uid", calendarUid)
                                if (organizer != null) put("organizer", organizer)
                                if (selfAttendeeStatus != null) {
                                    put("self_attendee_status", selfAttendeeStatus)
                                }
                                if (eventStatus != null) put("event_status", eventStatus)
                                rawIsOrganizer?.let { put("is_organizer", it == 1) }
                                rawHasAttendeeData?.let { put("has_attendee_data", it == 1) }
                                put("is_meeting", signals.isMeeting)
                                put("is_invitation", signals.isInvitation)
                            }.toString(),
                            privacyClass = "private_calendar",
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun Cursor.getNullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val PAST_WINDOW_MS = 7 * DAY_MS
        const val FUTURE_WINDOW_MS = 90 * DAY_MS
    }
}
