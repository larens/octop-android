package com.octopmobile.app.data.remote

import com.octopmobile.app.data.remote.dto.InterestMonitorCreateBody
import com.octopmobile.app.data.remote.dto.InterestMonitorDto
import com.octopmobile.app.data.remote.dto.InterestMonitorPatchBody
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

class InterestMonitorApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OctopApi
    private val monitor = """{"monitor_id":"m1","description":"Codex release","schedule":"morning","enabled":true,"baseline_established":false,"last_run_at":null,"last_status":null,"last_error":null,"last_result_count":0,"next_run_at":100,"delivery_backoff_until":120,"feedback_backoff_reason":"too_frequent","feedback_backoff_level":2,"created_at":1,"updated_at":1}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = OctopApi(server.url("/").toString(), OkHttpClient(), Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun monitorCrud_usesStructuredEndpoints() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[$monitor]"))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(monitor))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(monitor.replace("\"enabled\":true", "\"enabled\":false")))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"monitor\":$monitor,\"result_count\":8,\"new_count\":0,\"published_count\":0}"))

        val listed = api.interestMonitors("a")
        assertTrue(listed is ApiResult.Ok)
        val dto = (listed as ApiResult.Ok<List<InterestMonitorDto>>).value.single()
        assertEquals(120L, dto.deliveryBackoffUntil)
        assertEquals("too_frequent", dto.feedbackBackoffReason)
        assertEquals(2, dto.feedbackBackoffLevel)
        api.createInterestMonitor("a", InterestMonitorCreateBody("Codex release", "morning"))
        api.patchInterestMonitor("a", "m1", InterestMonitorPatchBody(enabled = false))
        api.runInterestMonitor("a", "m1")

        assertEquals("/api/agents/a/interest-monitors", server.takeRequest().path)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("PATCH", server.takeRequest().method)
        val run = server.takeRequest()
        assertEquals("POST", run.method)
        assertEquals("/api/agents/a/interest-monitors/m1/run", run.path)
    }
}
