package com.octopmobile.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class HistoryGroupTest {

    @Test
    fun `pinned conversation belongs to pinned group before checking date`() {
        val farInThePast = 0L

        assertEquals(HistoryGroup.Pinned, historyGroupFor(pinned = true, lastActiveAt = farInThePast))
    }

    @Test
    fun `recent conversations are grouped by relative day`() {
        val todayStart = startOfLocalDay(System.currentTimeMillis())
        val yesterdayStart = previousLocalDayStart(todayStart)
        val twoDaysAgoStart = previousLocalDayStart(yesterdayStart)
        val now = todayStart + 12 * 60 * 60 * 1000L

        assertEquals(HistoryGroup.Today, historyGroupFor(false, todayStart, now))
        assertEquals(HistoryGroup.Yesterday, historyGroupFor(false, yesterdayStart, now))
        assertEquals(
            HistoryGroup.Date(twoDaysAgoStart),
            historyGroupFor(false, twoDaysAgoStart, now),
        )
    }

    @Test
    fun `older date groups have stable ordering keys`() {
        val dayKey = 20_000L

        assertEquals("date:20000", HistoryGroup.Date(dayKey).key())
    }

    private fun startOfLocalDay(timestamp: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun previousLocalDayStart(dayStart: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
}
