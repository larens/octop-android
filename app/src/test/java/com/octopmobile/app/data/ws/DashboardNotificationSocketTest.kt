package com.octopmobile.app.data.ws

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardNotificationSocketTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeTodayCardPush_acceptsOnlyMinimalTodayEvent() {
        val decoded = decodeTodayCardPush(
            json,
            """{"type":"dashboard_push","event":"today.card_changed","agent_id":"agent-1","card_id":"card-1","priority":"normal"}""",
        )

        assertEquals(TodayCardPush("agent-1", "card-1"), decoded)
        assertNull(decodeTodayCardPush(json, """{"type":"dashboard_push","thread_id":"thread-1"}"""))
        assertNull(decodeTodayCardPush(json, "not-json"))
    }

    @Test
    fun decodeThreadPush_acceptsCronDeliveryButNotTodayCardEvent() {
        val decoded = decodeThreadPush(
            json,
            """{"type":"dashboard_push","agent_id":"agent-1","thread_id":"thread-1","text":"简报已生成"}""",
        )

        assertEquals(ThreadPush("agent-1", "thread-1", "简报已生成"), decoded)
        assertNull(
            decodeThreadPush(
                json,
                """{"type":"dashboard_push","event":"today.card_changed","agent_id":"agent-1","card_id":"card-1"}""",
            ),
        )
        assertNull(decodeThreadPush(json, """{"type":"dashboard_push","text":"missing ids"}"""))
        assertNull(decodeThreadPush(json, "not-json"))
    }
}
