package com.octopmobile.app.data.remote

import com.octopmobile.app.data.remote.dto.TodayCardPatchBody
import com.octopmobile.app.data.remote.dto.TodayCardActionBody
import com.octopmobile.app.data.remote.dto.TodayCardFeedbackBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TodayCardApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OctopApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = OctopApi(
            server.url("/").toString(),
            OkHttpClient(),
            Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun todayCards_readsEvidenceAndPatchesLifecycle() = runBlocking {
        val card = """{"card_id":"card-1","kind":"calendar","title":"Review","summary":"Tomorrow","why_now":"Soon","confidence":0.92,"risk_level":"A3","lifecycle":"unread","occurred_at":10,"snoozed_until":null,"action_prompt":"Help","created_at":1,"updated_at":2,"execution_thread_id":"thread-1","execution_turn_id":"turn-1","action_state":"running","action_updated_at":3,"evidence":[{"event_id":"event-1","source_type":"system_calendar","source_record_id":"calendar:1","event_type":"calendar.event","occurred_at":10,"privacy_class":"private_calendar"}]}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[$card]"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(card.replace("unread", "snoozed").replace("\"snoozed_until\":null", "\"snoozed_until\":99")))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(card))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(card.replace("running", "completed")))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"generated_at":10,"sources":[{"source_type":"system_calendar","state":"ok","last_success_at":9,"next_run_at":null,"item_count":2,"issue_count":0}]}""",
            ),
        )

        val listed = api.todayCards("agent-1")
        assertTrue(listed is ApiResult.Ok)
        assertEquals("event-1", (listed as ApiResult.Ok).value.single().evidence.single().eventId)
        assertEquals("A3", listed.value.single().riskLevel)
        assertEquals("thread-1", listed.value.single().executionThreadId)
        api.patchTodayCard("agent-1", "card-1", TodayCardPatchBody("snoozed", 99))

        assertEquals("/api/agents/agent-1/today/cards", server.takeRequest().path)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/agents/agent-1/today/cards/card-1", patch.path)
        assertEquals("{\"lifecycle\":\"snoozed\",\"snoozed_until\":99}", patch.body.readUtf8())

        api.sendTodayCardFeedback("agent-1", "card-1", TodayCardFeedbackBody("useful"))
        val feedback = server.takeRequest()
        assertEquals("POST", feedback.method)
        assertEquals("/api/agents/agent-1/today/cards/card-1/feedback", feedback.path)
        assertEquals("{\"feedback_type\":\"useful\"}", feedback.body.readUtf8())

        api.patchTodayCardAction(
            "agent-1",
            "card-1",
            TodayCardActionBody("completed", "thread-1", "turn-1"),
        )
        val action = server.takeRequest()
        assertEquals("PATCH", action.method)
        assertEquals("/api/agents/agent-1/today/cards/card-1/action", action.path)
        assertEquals(
            "{\"state\":\"completed\",\"thread_id\":\"thread-1\",\"turn_id\":\"turn-1\"}",
            action.body.readUtf8(),
        )

        val coverage = api.todayCoverage("agent-1")
        assertTrue(coverage is ApiResult.Ok)
        val coverageSources = (coverage as ApiResult.Ok).value.sources
        assertEquals("system_calendar", coverageSources.single().sourceType)
        assertEquals("ok", coverageSources.single().state)
        val coverageRequest = server.takeRequest()
        assertEquals("/api/agents/agent-1/today/sources", coverageRequest.path)
    }
}
