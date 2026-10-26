package com.octopmobile.app.data.remote

import com.octopmobile.app.data.remote.dto.CronJobCreateBody
import com.octopmobile.app.data.remote.dto.CronJobPatchBody
import com.octopmobile.app.data.remote.dto.isReadyForAutomation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutomationApiTest {
    private lateinit var server: MockWebServer
    private lateinit var json: Json
    private lateinit var api: OctopApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        api = OctopApi(server.url("/").toString(), OkHttpClient(), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun connectorInstances_onlyReadyConnectorsQualifyForRecommendations() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                [
                  {"instance_id":"news-1","kind":"tencent-news","display_name":"新闻","status":"active","mcp_server_name":"tencent-news__news-1","has_credentials":true,"usable":true},
                  {"instance_id":"mail-1","kind":"qq-mail","status":"disabled","mcp_server_name":"qq-mail__mail-1","has_credentials":true,"usable":true},
                  {"instance_id":"read-1","kind":"wechat-reading","status":"active","mcp_server_name":"wechat-reading__read-1","has_credentials":false,"usable":true},
                  {"instance_id":"calendar-1","kind":"feishu-cli","status":"active","mcp_server_name":"feishu-cli__calendar-1","has_credentials":true,"usable":false}
                ]
                """.trimIndent(),
            ),
        )

        val result = api.connectorInstances()

        assertTrue(result is ApiResult.Ok)
        val ready = (result as ApiResult.Ok).value.filter { it.isReadyForAutomation() }
        assertEquals(listOf("tencent-news__news-1"), ready.map { it.mcpServerName })
        assertEquals("/api/connector-instances", server.takeRequest().path)
    }

    @Test
    fun createCronJob_sendsSelectedConnectorServerNames() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"id":"cron-1","name":"每日新闻简报","trigger":"cron:0 8 * * *","prompt":"brief"}""",
                status = 201,
            ),
        )

        val result = api.createCronJob(
            "agent-1",
            CronJobCreateBody(
                name = "每日新闻简报",
                trigger = "cron:0 8 * * *",
                prompt = "brief",
                enabled = true,
                mcpServers = listOf("tencent-news__news-1"),
            ),
        )

        assertTrue(result is ApiResult.Ok)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/agents/agent-1/cron", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("cron:0 8 * * *", body.getValue("trigger").jsonPrimitive.content)
        assertEquals(
            listOf("tencent-news__news-1"),
            body.getValue("mcp_servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun patchCronJob_bindsSelectedConnectorServerNames() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"id":"cron-1","name":"阅读总结","trigger":"cron:0 20 * * 0","prompt":"总结本周阅读","mcp_servers":["wechat-reading__read-1"]}""",
            ),
        )

        val result = api.patchCronJob(
            "agent-1",
            "cron-1",
            CronJobPatchBody(mcpServers = listOf("wechat-reading__read-1")),
        )

        assertTrue(result is ApiResult.Ok)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/agents/agent-1/cron/cron-1", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            listOf("wechat-reading__read-1"),
            body.getValue("mcp_servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun jsonResponse(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
