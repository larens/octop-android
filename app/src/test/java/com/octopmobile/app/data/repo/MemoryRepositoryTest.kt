package com.octopmobile.app.data.repo

import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
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

class MemoryRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun snapshot_preservesDiaryTitleInsightAndNewestFirst() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path?.contains("terminal/about_me") == true -> jsonResponse("""{"summary":"About me"}""")
                request.path?.contains("episodes/list") == true -> jsonResponse(
                    """
                    {"episodes":[
                      {"id":"older","title":"An earlier day","summary":"Old work","reflection":"I learned patience","created_at":"2026-10-24T08:00:00Z"},
                      {"episode_id":"newer","headline":"Putting the pieces together","story":"Today I solved the crash","growth":"I understand your constraints better","occurred_at":"2026-10-25T08:00:00Z"},
                      {"id":"same-day-candidate","summary":"Earlier candidate","occurred_at":"2026-10-25T06:00:00Z"}
                    ]}
                    """.trimIndent(),
                )
                else -> jsonResponse("[]")
            }
        }

        val api = OctopApi(
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )

        val result = ServerMemoryRepository(api).snapshot("assistant")

        assertTrue(result is ApiResult.Ok)
        val diaries = (result as ApiResult.Ok).value.episodes
        assertEquals(listOf("newer", "older"), diaries.map { it.id })
        assertEquals("Putting the pieces together", diaries.first().title)
        assertEquals("I understand your constraints better", diaries.first().insight)
    }

    @Test
    fun snapshot_usesPreferredAddressAndHidesScheduledTaskMemories() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path?.contains("terminal/about_me") == true -> jsonResponse(
                    """{"items":[{"assertion":"用户喜欢直接给结论"}]}""",
                )
                request.path?.contains("terminal/current_focus") == true -> jsonResponse(
                    """
                    {"items":[
                      {"kind":"Task","assertion":"已创建定时任务：每天 8:00 执行，cron:0 8 * * *"},
                      {"kind":"Task","assertion":"正在完善 Android 客户端的记忆页面"}
                    ]}
                    """.trimIndent(),
                )
                request.path?.contains("terminal/things_you_told_me") == true -> jsonResponse(
                    """
                    {"items":[
                      {"kind":"Fact","assertion":"助手以「武老师」称呼用户，用户未提出异议。"},
                      {"kind":"Fact","assertion":"定时任务的触发规则为 cron:0 20 * * 0"},
                      {"kind":"Fact","assertion":"用户最近在关注 AI 落地"}
                    ]}
                    """.trimIndent(),
                )
                else -> jsonResponse("[]")
            }
        }

        val api = OctopApi(
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )

        val result = ServerMemoryRepository(api).snapshot("assistant")

        assertTrue(result is ApiResult.Ok)
        val portrait = (result as ApiResult.Ok).value.portrait
        assertEquals("武老师", portrait.preferredAddress)
        assertEquals(listOf("正在完善 Android 客户端的记忆页面"), portrait.focus)
        assertTrue(portrait.truths.any { it.contains("用户最近在关注 AI 落地") })
        assertTrue(portrait.truths.none { it.contains("定时任务") || it.contains("cron:") })
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
