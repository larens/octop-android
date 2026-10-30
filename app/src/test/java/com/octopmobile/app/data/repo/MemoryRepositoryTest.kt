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
                    """
                    {
                      "items":[{"assertion":"用户喜欢直接给结论"}],
                      "profile_subject":"示例用户",
                      "profile_markdown":"示例用户（被助手称为「示例老师」）长期关注产品与 AI。\n\n### 工作方式\n- 喜欢先看结论 (atom-123456-abc)。\n- 会把想法沉淀成知识库 (abcdef-123)。"
                    }
                    """.trimIndent(),
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
                      {"kind":"Fact","assertion":"助手以「示例老师」称呼用户，用户未提出异议。"},
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
        assertEquals("示例老师", portrait.preferredAddress)
        assertEquals(listOf("正在完善 Android 客户端的记忆页面"), portrait.focus)
        assertTrue(portrait.truths.any { it.contains("用户最近在关注 AI 落地") })
        assertTrue(portrait.truths.none { it.contains("定时任务") || it.contains("cron:") })
        assertTrue(
            portrait.summary,
            portrait.summary.startsWith("你（被助手称为「示例老师」）长期关注产品与 AI。"),
        )
        assertTrue(portrait.summary.contains("喜欢先看结论。"))
        assertTrue(!portrait.summary.contains("atom-") && !portrait.summary.contains("###"))
    }

    @Test
    fun snapshot_prefersDailyWorkDiariesOverEpisodeFragments() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path?.contains("terminal/about_me") == true -> jsonResponse("""{"items":[]}""")
                request.path?.contains("memory/diaries/list") == true -> jsonResponse(
                    """
                    {"items":[{
                      "id":"diary-day",
                      "period_key":"2026-10-28",
                      "occurred_at":"2026-10-27T16:00:00Z",
                      "title":"把一天的协作真正收拢",
                      "summary":"今天推进了记忆页改造。\n\n你要求日记覆盖工作、学习与智能体成长。",
                      "growth":"我更清楚应该先核对生成规则。",
                      "emotion":"reflective",
                      "source_count":12
                    }]}
                    """.trimIndent(),
                )
                request.path?.contains("episodes/list") == true -> jsonResponse(
                    """{"episodes":[{"id":"fragment","summary":"过于简短的单条片段","occurred_at":"2026-10-28T08:00:00Z"}]}""",
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
        assertEquals(listOf("diary-day"), diaries.map { it.id })
        assertEquals("把一天的协作真正收拢", diaries.first().title)
        assertTrue(diaries.first().summary.contains("工作、学习与智能体成长"))
    }

    @Test
    fun snapshot_prefersNaturalProfileNarrativeOverRawEntityPage() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path?.contains("terminal/about_me") == true -> jsonResponse(
                    """
                    {
                      "items":[],
                      "profile_subject":"示例用户",
                      "profile_markdown":"示例用户（被助手称为「示例老师」）现居示例城市。\n\n### 身份与家庭\n- 生日为 1990 年 6 月 15 日",
                      "profile_narrative":"我认识你，是从合成测试资料开始的。\n\n你的工作主线一直围绕产品和 AI 展开。\n\n你习惯先把变化抓进来，再慢慢整理成自己的判断。\n\n和你协作时，我大概能理解你为什么总要求先说依据。",
                      "profile_brief":"最近你仍在围绕产品与 AI 推进工作，也在把零散信号整理成可复用的判断。和我协作时，先说依据、写操作前确认，是重要边界。"
                    }
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
        assertEquals("示例老师", portrait.preferredAddress)
        assertTrue(portrait.summary.startsWith("我认识你，是从合成测试资料开始的。"))
        assertTrue(portrait.brief.startsWith("最近你仍在围绕产品与 AI"))
        assertTrue(portrait.summary.contains("你习惯先把变化抓进来"))
        assertTrue(!portrait.summary.contains("姓名纠正为"))
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
