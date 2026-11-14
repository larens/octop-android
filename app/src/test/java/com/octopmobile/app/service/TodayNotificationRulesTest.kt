package com.octopmobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TodayNotificationRulesTest {
    @Test
    fun channelMappingMatchesServerPriority() {
        assertEquals(TodayNotifications.CHANNEL_IMPORTANT, TodayNotificationRules.channelFor("p0"))
        assertEquals(TodayNotifications.CHANNEL_BRIEFING, TodayNotificationRules.channelFor("P1"))
        assertNull(TodayNotificationRules.channelFor("p2"))
        assertNull(TodayNotificationRules.channelFor("normal"))
    }

    @Test
    fun notificationsRespectBothSwitches() {
        assertTrue(TodayNotificationRules.shouldNotify("p0", true, true))
        assertFalse(TodayNotificationRules.shouldNotify("p0", false, true))
        assertFalse(TodayNotificationRules.shouldNotify("p0", true, false))
        assertFalse(TodayNotificationRules.shouldNotify("p2", true, true))
    }

    @Test
    fun dedupeWindowIsSixHours() {
        val now = 10_000_000L
        assertTrue(TodayNotificationRules.shouldDedupe(now - 1000, now))
        assertFalse(
            TodayNotificationRules.shouldDedupe(
                now - TodayNotificationRules.DEDUPE_WINDOW_MILLIS,
                now,
            ),
        )
        assertFalse(TodayNotificationRules.shouldDedupe(null, now))
    }

    @Test
    fun quietHoursWrapMidnight() {
        assertFalse(TodayNotificationRules.isQuietHours(LocalTime.of(21, 59)))
        assertTrue(TodayNotificationRules.isQuietHours(LocalTime.of(22, 0)))
        assertTrue(TodayNotificationRules.isQuietHours(LocalTime.of(3, 0)))
        assertTrue(TodayNotificationRules.isQuietHours(LocalTime.of(7, 29)))
        assertFalse(TodayNotificationRules.isQuietHours(LocalTime.of(7, 30)))
    }

    @Test
    fun quietHoursOnlySuppressBriefings() {
        val night = LocalTime.of(23, 0)
        assertTrue(
            TodayNotificationRules.shouldPost(
                TodayNotifications.CHANNEL_IMPORTANT,
                quietHoursEnabled = true,
                now = night,
                shownToday = 0,
            ),
        )
        assertFalse(
            TodayNotificationRules.shouldPost(
                TodayNotifications.CHANNEL_BRIEFING,
                quietHoursEnabled = true,
                now = night,
                shownToday = 0,
            ),
        )
        assertTrue(
            TodayNotificationRules.shouldPost(
                TodayNotifications.CHANNEL_BRIEFING,
                quietHoursEnabled = false,
                now = night,
                shownToday = 0,
            ),
        )
    }

    @Test
    fun dailyLimitStopsBriefingsAfterFive() {
        val morning = LocalTime.of(9, 0)
        assertTrue(TodayNotificationRules.canReachDailyLimit(4))
        assertFalse(TodayNotificationRules.canReachDailyLimit(5))
        assertFalse(
            TodayNotificationRules.shouldPost(
                TodayNotifications.CHANNEL_BRIEFING,
                quietHoursEnabled = false,
                now = morning,
                shownToday = 5,
            ),
        )
        assertTrue(
            TodayNotificationRules.shouldPost(
                TodayNotifications.CHANNEL_IMPORTANT,
                quietHoursEnabled = false,
                now = morning,
                shownToday = 5,
            ),
        )
    }

    @Test
    fun dailyCountResetsOnNewDay() {
        assertEquals(3, TodayNotificationRules.dailyCount("2026-11-13", "2026-11-13", 3))
        assertEquals(0, TodayNotificationRules.dailyCount("2026-11-12", "2026-11-13", 3))
        assertEquals(0, TodayNotificationRules.dailyCount(null, "2026-11-13", 3))
    }
}
