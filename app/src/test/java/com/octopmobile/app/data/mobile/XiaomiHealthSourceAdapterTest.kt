package com.octopmobile.app.data.mobile

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaomiHealthSourceAdapterTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `aggregates system step intervals by local date`() {
        val late = ZonedDateTime.of(2026, 10, 27, 23, 55, 0, 0, zone)
        val early = ZonedDateTime.of(2026, 10, 28, 7, 10, 0, 0, zone)
        val noon = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, zone)

        val daily = aggregateXiaomiSteps(
            listOf(
                XiaomiStepSample(late.toInstant().toEpochMilli(), 120),
                XiaomiStepSample(early.toInstant().toEpochMilli(), 800),
                XiaomiStepSample(noon.toInstant().toEpochMilli(), 255),
            ),
            zone,
        )

        assertEquals(120L, daily[LocalDate.of(2026, 10, 27)])
        assertEquals(1_055L, daily[LocalDate.of(2026, 10, 28)])
    }

    @Test
    fun `ignores non-positive step rows instead of inventing activity`() {
        val timestamp = ZonedDateTime.of(2026, 10, 28, 9, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

        val daily = aggregateXiaomiSteps(
            listOf(
                XiaomiStepSample(timestamp, 0),
                XiaomiStepSample(timestamp + 60_000, -5),
            ),
            zone,
        )

        assertEquals(emptyMap<LocalDate, Long>(), daily)
    }
}
