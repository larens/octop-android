package com.octopmobile.app.data.remote

import com.octopmobile.app.data.remote.dto.ArtifactShareBody
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

class ArtifactShareApiTest {
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
    fun artifactShare_postsExactThreadPathAndAgent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"url":"https://octop.example/api/s/Ab3xK9Q2z7","expires_at":123,"filename":"report.html"}""",
                ),
        )

        val result = api.createArtifactShare(
            agentId = "AGT-HOME",
            threadId = "thread-1",
            body = ArtifactShareBody(path = "outbound/report.html", agentId = "AGT-EXPERT"),
        )

        assertTrue(result is ApiResult.Ok)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            "/api/agents/AGT-HOME/threads/thread-1/artifact-shares",
            request.path,
        )
        assertEquals(
            """{"path":"outbound/report.html","agent_id":"AGT-EXPERT"}""",
            request.body.readUtf8(),
        )
    }
}
