package com.octopmobile.app.data.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSignalPromptTest {
    @Test
    fun `calendar prompt contains facts and safety boundary`() {
        val prompt = mobileSignalPrompt(
            eventType = "calendar.event",
            summary = "产品评审",
            payloadJson = """{"title":"产品评审","starts_at":1000,"ends_at":2000,"location":"会议室 A"}""",
        )

        assertTrue(prompt.contains("产品评审"))
        assertTrue(prompt.contains("会议室 A"))
        assertTrue(prompt.contains("未经我确认"))
        assertFalse(prompt.contains("开始时间：1000"))
    }

    @Test
    fun `share prompt includes content and attachment names without base64`() {
        val prompt = mobileSignalPrompt(
            eventType = "share.inbox_item",
            summary = "分享文本与 1 个附件",
            payloadJson = """{"text":"需要跟进的资料","attachments":[{"name":"brief.pdf","content_base64":"SECRET"}]}""",
        )

        assertTrue(prompt.contains("需要跟进的资料"))
        assertTrue(prompt.contains("brief.pdf"))
        assertFalse(prompt.contains("SECRET"))
        assertFalse(prompt.contains("content_base64"))
    }

    @Test
    fun `health prompts expose daily metrics without inventing unavailable data`() {
        val heart = mobileSignalPrompt(
            eventType = "health.heart_rate_daily",
            summary = "heart",
            payloadJson = """{"date":"2026-10-28","average_bpm":72,"min_bpm":52,"max_bpm":148,"measurements":96}""",
        )
        val oxygen = mobileSignalPrompt(
            eventType = "health.oxygen_saturation_daily",
            summary = "oxygen",
            payloadJson = """{"average_percent":96.5,"min_percent":94,"measurements":12}""",
        )
        val hrv = mobileSignalPrompt(
            eventType = "health.hrv_daily",
            summary = "hrv",
            payloadJson = """{"average_ms":41.6,"measurements":4}""",
        )

        assertTrue(heart.contains("平均心率：72 bpm"))
        assertTrue(heart.contains("最高心率：148 bpm"))
        assertTrue(oxygen.contains("平均血氧：97%"))
        assertTrue(oxygen.contains("最低血氧：94%"))
        assertTrue(hrv.contains("HRV（压力参考）：42 ms"))
        assertFalse(hrv.contains("压力："))
    }
}
