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
}
