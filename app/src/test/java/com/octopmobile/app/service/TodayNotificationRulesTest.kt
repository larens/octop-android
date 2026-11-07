package com.octopmobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
