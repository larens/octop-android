package com.octopmobile.app.data.remote

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

class MainConversationApiTest {
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
    fun phase0ReadsUseStableConversationTaskAndTraceRoutes() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "conversation_id":"conv-1","agent_id":"agent-1","user_id":7,
                  "router_thread_id":"thread-1","state":{"mode":"shadow"},
                  "summary":"主会话","last_conversation_seq":9,
                  "created_at":10,"updated_at":11,"archived_at":null
                }
                """,
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                [{
                  "task_id":"task-1","conversation_id":"conv-1","task_type":"calendar",
                  "title":"检查冲突","subject_key":"week-44","objective":"找冲突",
                  "status":"needs_user","requires_user":true,"capsule":{"next_step":"确认"},
                  "priority":2,"created_at":12,"updated_at":13,"expires_at":null
                }]
                """,
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                [{
                  "event_id":"event-5","conversation_id":"conv-1","trace_id":"trace-1",
                  "task_id":"task-1","run_id":null,"parent_event_id":null,"seq":5,
                  "schema_version":1,"event_type":"task.updated","status":"needs_user",
                  "summary":"等待确认","payload_ref":null,"usage":{"input_tokens":20},
                  "created_at":14
                }]
                """,
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                {
                  "trace_id":"trace-1","events":[],"tasks":[],
                  "context_packs":[{
                    "pack_id":"pack-1","conversation_id":"conv-1","trace_id":"trace-1",
                    "policy_version":"router-phase0-v1","model":"test-model","used_tokens":120,
                    "sections":[],"included_refs":[],"omitted_refs":[],"created_at":15
                  }]
                }
                """,
            ),
        )

        val conversation = api.mainConversation("agent-1")
        assertTrue(conversation is ApiResult.Ok)
        assertEquals("conv-1", (conversation as ApiResult.Ok).value.conversationId)
        assertEquals("/api/agents/agent-1/main-conversation", server.takeRequest().path)

        val tasks = api.conversationTasks("agent-1", status = "needs_user", limit = 20)
        assertTrue(tasks is ApiResult.Ok)
        assertEquals(true, (tasks as ApiResult.Ok).value.single().requiresUser)
        assertEquals(
            "/api/agents/agent-1/tasks?limit=20&status=needs_user",
            server.takeRequest().path,
        )

        val events = api.mainConversationEvents("agent-1", afterSeq = 4, limit = 20)
        assertTrue(events is ApiResult.Ok)
        assertEquals(5, (events as ApiResult.Ok).value.single().seq)
        assertEquals(
            "/api/agents/agent-1/main-conversation/events?after_seq=4&limit=20",
            server.takeRequest().path,
        )

        val trace = api.conversationTrace("agent-1", "trace-1", limit = 100)
        assertTrue(trace is ApiResult.Ok)
        assertEquals("pack-1", (trace as ApiResult.Ok).value.contextPacks.single().packId)
        assertEquals(
            "/api/agents/agent-1/traces/trace-1?include_payload=false&limit=100",
            server.takeRequest().path,
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body.trimIndent())
}
