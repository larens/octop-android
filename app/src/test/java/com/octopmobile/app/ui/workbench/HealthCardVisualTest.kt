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
        assertNotEquals(healthMetricColor("resting_heart"), healthMetricColor("heart"))
        assertNotEquals(healthMetricColor("calories"), healthMetricColor("exercise_minutes"))
        assertNotEquals(healthMetricColor("activity_minutes"), healthMetricColor("exercise_minutes"))
        assertNotEquals(healthMetricColor("deep_ratio"), healthMetricColor("wake_time"))
    }

    @Test
    fun parsesRicherXiaomiStyleHealthMetrics() {
        val richerSummary = """
            【趋势摘要】
            睡眠和活动趋势已更新。

            【健康可视化】
            {"w":"weekly","m":[
              {"k":"steps","v":8230,"d":"8,230","u":"步","t":8000,"c":"columns","s":[7100,7600,6900,8230]},
              {"k":"sleep","v":390,"d":"6 小时 30 分","u":"小时","t":480,"c":"columns","s":[420,390,360,390]},
              {"k":"calories","v":545,"d":"545","u":"千卡","c":"columns","s":[520,560,540,545]},
              {"k":"activity_minutes","v":29,"d":"29","u":"分钟","c":"columns","s":[18,22,25,29]},
              {"k":"exercise_minutes","v":45,"d":"45","u":"分钟","c":"columns","s":[0,20,0,45]},
              {"k":"resting_heart","v":71,"d":"71","u":"bpm","c":"columns","s":[72,73,70,71]},
              {"k":"deep_ratio","v":23,"d":"23","u":"%","c":"columns","s":[21,24,22,23]},
              {"k":"wake_time","v":375,"d":"06:15","u":"","c":"line","s":[360,390,375,375]}
            ]}
        """.trimIndent()

        val visualization = parseHealthVisualization(richerSummary)

        assertEquals("weekly", visualization?.window)
        assertEquals(
            listOf(
                "steps",
                "sleep",
                "calories",
                "activity_minutes",
                "exercise_minutes",
                "resting_heart",
                "deep_ratio",
                "wake_time",
            ),
            visualization?.metrics?.map { it.key },
        )
    }

    @Test
    fun keepsPrimaryRatioBarsCompact() {
        assertEquals(14.dp, healthPrimaryTrendHeight("ratio"))
        assertEquals(46.dp, healthPrimaryTrendHeight("line"))
        assertEquals(46.dp, healthPrimaryTrendHeight("columns"))
    }
}
