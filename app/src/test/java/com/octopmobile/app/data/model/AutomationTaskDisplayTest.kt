package com.octopmobile.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationTaskDisplayTest {
    @Test
    fun removesMachineOnlyCommentsAndBlankLines() {
        val prompt = """
            <!-- octop-caiyun:v2 -->

            查询今天天气
            <!-- octop-summarize:v2 -->
            整理日程
        """.trimIndent()

        assertEquals("查询今天天气\n整理日程", cleanAutomationTaskText(prompt))
    }

    @Test
    fun derivesReadableTitleWhenStoredTitleIsOnlyAComment() {
        val title = automationTaskDisplayTitle(
            name = "<!-- octop-caiyun:v2 -->",
            prompt = "<!-- octop-caiyun:v2 -->\n查询今天天气并整理日程",
            id = "c8W9REG",
        )

        assertEquals("查询今天天气并整理日程", title)
    }
}
