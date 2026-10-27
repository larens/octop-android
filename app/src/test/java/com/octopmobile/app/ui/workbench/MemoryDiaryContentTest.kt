package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.repo.MemoryEpisode
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDiaryContentTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = at(2026, 10, 27, 18, 0)

    @Test
    fun keepsTheLastSevenCalendarDaysInNewestFirstOrder() {
        val diaries = listOf(
            episode("outside", at(2026, 10, 20, 23, 59)),
            episode("boundary", at(2026, 10, 21, 0, 0)),
            episode("middle", at(2026, 10, 24, 9, 0)),
            episode("yesterday", at(2026, 10, 26, 22, 0)),
            episode("today", at(2026, 10, 27, 17, 0)),
            episode("future", at(2026, 10, 28, 9, 0)),
            episode("no-date", null),
        )

        val result = recentDiaries(diaries, now, zone)

        assertEquals(
            listOf("today", "yesterday", "middle", "boundary"),
            result.map { it.id },
        )
    }

    @Test
    fun keepsOnlyTheLatestDiaryPerDayAndCapsTheShelfAtSeven() {
        val diaries = (21..27).flatMap { day ->
            buildList {
                add(episode("day-$day", at(2026, 10, day, 8, 0)))
                if (day == 27) add(episode("today-late", at(2026, 10, 27, 17, 0)))
            }
        }

        val result = recentDiaries(diaries, now, zone)

        assertEquals(7, result.size)
        assertEquals("today-late", result.first().id)
        assertEquals((26 downTo 21).map { "day-$it" }, result.drop(1).map { it.id })
        assertTrue(result.mapNotNull { it.occurredAt }.zipWithNext().all { (a, b) -> a > b })
    }

    private fun episode(id: String, occurredAt: Long?): MemoryEpisode = MemoryEpisode(
        id = id,
        title = id,
        emotion = null,
        summary = "summary",
        insight = null,
        intensity = null,
        occurredAt = occurredAt,
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
