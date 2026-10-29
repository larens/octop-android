package com.octopmobile.app.ui.workbench

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HealthCardVisualTest {
    private val summary = """
        【今日概览】
        步数：7,240 步

        【健康可视化】
        {"w":"evening","m":[{"k":"steps","v":7240,"d":"7,240","u":"步","t":8000,"c":"ring","s":[null,5200,6400,7100,7240]},{"k":"heart","v":72,"d":"72","u":"bpm","c":"line","s":[68,71,70,72]}]}
    """.trimIndent()

    @Test
    fun parsesCompactHealthVisualizationWithoutExposingJsonInPreview() {
        val visualization = parseHealthVisualization(summary)

        assertEquals("evening", visualization?.window)
        assertEquals(listOf("steps", "heart"), visualization?.metrics?.map { it.key })
        assertEquals(8000.0, visualization?.metrics?.first()?.target)
        assertFalse(todayCardSummaryPreview(summary).contains("\"w\""))
        assertEquals("步数：7,240 步", todayCardSummaryPreview(summary))
    }

    @Test
    fun recognizesHealthChartModesAndMetricPalette() {
        assertEquals(HealthChartMode.RING, healthChartMode("ring"))
        assertEquals(HealthChartMode.BAR, healthChartMode("bar"))
        assertEquals(HealthChartMode.COLUMNS, healthChartMode("columns"))
        assertEquals(HealthChartMode.LINE, healthChartMode("line"))
        assertEquals(HealthChartMode.RATIO, healthChartMode("ratio"))
        assertEquals(HealthChartMode.COLUMNS, healthChartMode("unknown"))

        assertNotEquals(healthMetricColor("steps"), healthMetricColor("sleep"))
        assertNotEquals(healthMetricColor("heart"), healthMetricColor("oxygen"))
        assertNotEquals(healthMetricColor("stress"), healthMetricColor("standing"))
    }

    @Test
    fun keepsPrimaryRatioBarsCompact() {
        assertEquals(14.dp, healthPrimaryTrendHeight("ratio"))
        assertEquals(46.dp, healthPrimaryTrendHeight("line"))
        assertEquals(46.dp, healthPrimaryTrendHeight("columns"))
    }
}
