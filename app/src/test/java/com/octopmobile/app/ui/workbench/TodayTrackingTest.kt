package com.octopmobile.app.ui.workbench

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayTrackingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 11, 7, 10, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    private val json = """
        {
          "subject": "AI 上生态城影像大赛",
          "stage": "专家评审",
          "status": "in_progress",
          "progress": 55,
          "progress_label": "赛程进度",
          "phases": [
            {"label": "征集", "starts_at": 1788537600, "ends_at": 1792511940, "state": "done"},
            {"label": "初审", "starts_at": 1792512000, "ends_at": 1793894340, "state": "done"},
            {"label": "专家评审", "starts_at": 1793894400, "ends_at": 1795190340, "state": "active"},
            {"label": "公示颁奖", "starts_at": 1795190400}
          ],
          "metrics": [
            {"label": "传播分", "value": "50%"},
            {"label": "评委分", "value": "50%"}
          ],
          "missing": ["抖音播放量"],
          "next_milestone": "专家评审结束",
          "next_check_at": 1794531600,
          "next_action": "补录四平台数据"
        }
    """.trimIndent()

    @Test
    fun parsesStructuredTracking() {
        val tracking = requireNotNull(parseTodayTracking(json))
        assertEquals("AI 上生态城影像大赛", tracking.subject)
        assertEquals("专家评审", tracking.stage)
        assertEquals(55, tracking.progress)
        assertEquals(4, tracking.phases.size)
        assertEquals("补录四平台数据", tracking.nextAction)
        assertEquals(listOf("抖音播放量"), tracking.missing)
    }

    @Test
    fun derivesPhaseStateFromDatesWhenServerStateIsMissing() {
        val tracking = requireNotNull(parseTodayTracking(json))
        val done = tracking.phases[1]
        val active = tracking.phases[2]
        val upcoming = tracking.phases[3]

        assertEquals(
            TodayTrackingPhaseState.DONE,
            done.resolvedState(now, tracking.stage, tracking.status),
        )
        assertEquals(
            TodayTrackingPhaseState.ACTIVE,
            active.resolvedState(now, tracking.stage, tracking.status),
        )
        assertEquals(
            TodayTrackingPhaseState.UPCOMING,
            upcoming.resolvedState(now, tracking.stage, tracking.status),
        )
    }

    @Test
    fun formatsPhaseRangeAndNextCheck() {
        val tracking = requireNotNull(parseTodayTracking(json))
        assertEquals("11/6–11/20", tracking.phases[2].dateRangeLabel(zone))
        assertEquals(
            "11/13 09:00",
            trackingCheckLabel(requireNotNull(tracking.nextCheckAt).asEpochMillis()!!, zone),
        )
    }

    @Test
    fun ignoresEmptyOrMalformedTracking() {
        assertNull(parseTodayTracking(null))
        assertNull(parseTodayTracking(""))
        assertNull(parseTodayTracking("{not-json"))
        assertNull(parseTodayTracking("""{"subject":"x","stage":""}"""))
    }
}
