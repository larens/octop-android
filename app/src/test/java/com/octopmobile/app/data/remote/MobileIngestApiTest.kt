package com.octopmobile.app.data.remote

import com.octopmobile.app.data.remote.dto.MobileIngestBatchBody
import com.octopmobile.app.data.remote.dto.MobileIngestEventDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MobileIngestApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OctopApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = OctopApi(server.url("/").toString(), OkHttpClient(), json)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun ingestMobileEvents_sendsStableIdsAndReadsPerItemAcknowledgement() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"accepted_event_ids":["event-1"],"rejected_event_ids":["event-2"]}"""),
        )
        val result = api.ingestMobileEvents(
            "agent-1",
            MobileIngestBatchBody(
                deviceId = "device-1",
                events = listOf(
                    MobileIngestEventDto("event-1", "share_inbox", null, "share.inbox_item", 1, 2, buildJsonObject {}, "private_user_content"),
                    MobileIngestEventDto("event-2", "system_calendar", "calendar:1", "calendar.event", 3, 4, buildJsonObject {}, "private_calendar"),
                ),
            ),
        )

        assertTrue(result is ApiResult.Ok)
        assertEquals(listOf("event-1"), (result as ApiResult.Ok).value.acceptedEventIds)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/agents/agent-1/mobile/ingest", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("device-1", body.getValue("device_id").jsonPrimitive.content)
        assertEquals(listOf("event-1", "event-2"), body.getValue("events").jsonArray.map {
            it.jsonObject.getValue("event_id").jsonPrimitive.content
        })
    }
}
