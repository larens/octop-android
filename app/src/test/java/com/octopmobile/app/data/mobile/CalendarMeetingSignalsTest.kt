package com.octopmobile.app.data.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarMeetingSignalsTest {
    @Test
    fun `external organizer with attendees is an invitation`() {
        val signals = calendarMeetingSignals(
            organizer = "wuhao58@xiaomi.com",
            isOrganizer = false,
            hasAttendeeData = true,
        )

        assertTrue(signals.isMeeting)
        assertTrue(signals.isInvitation)
    }

    @Test
    fun `self organized meeting is not an invitation`() {
        val signals = calendarMeetingSignals(
            organizer = "me@example.com",
            isOrganizer = true,
            hasAttendeeData = true,
        )

        assertTrue(signals.isMeeting)
        assertFalse(signals.isInvitation)
    }

    @Test
    fun `personal calendar entry is not a meeting`() {
        val signals = calendarMeetingSignals(
            organizer = "9164228",
            isOrganizer = true,
            hasAttendeeData = false,
        )

        assertFalse(signals.isMeeting)
        assertFalse(signals.isInvitation)
    }
}
