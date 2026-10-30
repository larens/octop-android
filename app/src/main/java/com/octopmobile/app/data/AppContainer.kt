package com.octopmobile.app.data

import android.content.Context
import com.octopmobile.app.data.attachments.AttachmentStore
import com.octopmobile.app.data.db.OctopDatabase
import com.octopmobile.app.data.mobile.LocalPayloadCipher
import com.octopmobile.app.data.mobile.MobileDataRepository
import com.octopmobile.app.data.prefs.SecurePrefs
import com.octopmobile.app.data.remote.AuthInterceptor
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.repo.AutomationRepository
import com.octopmobile.app.data.repo.ChatRepository
import com.octopmobile.app.data.repo.InstanceRepository
import com.octopmobile.app.data.repo.InterestMonitorRepository
import com.octopmobile.app.data.repo.MemoryRepository
import com.octopmobile.app.data.repo.NaturalLanguageTaskRepository
import com.octopmobile.app.data.repo.ServerMemoryRepository
import com.octopmobile.app.data.repo.TagRepository
import com.octopmobile.app.data.repo.ThreadRepository
import com.octopmobile.app.data.repo.TodayRepository
import com.octopmobile.app.data.repo.TodayGoalRepository
import com.octopmobile.app.data.settings.SettingsPreferences
import com.octopmobile.app.data.ws.ChatSocket
import com.octopmobile.app.data.ws.DashboardNotificationSocket
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 全局依赖容器（手写 DI）。
 *
 * 这里只放「与具体实例无关」的东西。带实例地址的客户端（REST / WS）由 [api] 按
 * baseUrl 构造，见 docs/spec.md §6。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 宽松解析：Octop 各版本会增删字段，客户端不应该因为多了一个未知键就整条消息解析失败。
     * 这是客户端向后兼容的第一道防线（见 docs/spec.md §21「上游无版本号」）。
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 裸客户端：不带令牌，用于能力探测这类「还没登录」的请求。 */
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val database: OctopDatabase = OctopDatabase.get(appContext)

    val prefs: AppPrefs = AppPrefs(appContext)
    val settings: SettingsPreferences = SettingsPreferences(appContext)

    /** 令牌与实例归属（docs/spec.md §15）。 */
    val securePrefs: SecurePrefs = SecurePrefs(appContext)
    private val mobileCipher by lazy { LocalPayloadCipher() }
    private val mobileDevicePrefs by lazy {
        appContext.getSharedPreferences("mobile_device", Context.MODE_PRIVATE)
    }

    /** 附件二进制的落盘与容量控制（docs/spec.md §13）。实例无关，全局一个即可。 */
    val attachments: AttachmentStore by lazy { AttachmentStore(appContext, database.attachments()) }

    @Volatile
    private var cachedApi: OctopApi? = null

    /**
     * 取当前实例的 REST 客户端。本版只支持一台实例（docs/spec.md §20），所以这里只缓存
     * 一个槽位：地址变了就整体换掉，旧客户端连同它的连接池一起被回收。
     *
     * 带鉴权的客户端由 [okHttp] `newBuilder()` 派生 —— OkHttp 会共享连接池与调度器，
     * 因此派生本身几乎没有开销。
     */
    fun api(baseUrl: String): OctopApi {
        cachedApi?.takeIf { it.baseUrl == baseUrl }?.let { return it }
        return synchronized(this) {
            cachedApi?.takeIf { it.baseUrl == baseUrl } ?: OctopApi(
                baseUrl = baseUrl,
                client = okHttp.newBuilder()
                    .addInterceptor(AuthInterceptor(securePrefs, baseUrl))
                    .build(),
                json = json,
            ).also { cachedApi = it }
        }
    }

    /**
     * 每开一个会话建一条 WS 连接（services 层一个实例同时只服务于一个前台会话）。
     * 鉴权走 query 参数而不是请求头（docs/protocol.md §8 第 1 条），所以直接用裸客户端。
     */
    fun chatSocket(baseUrl: String, agentId: String): ChatSocket = ChatSocket(
        baseUrl = baseUrl,
        agentId = agentId,
        tokenProvider = { securePrefs.tokenFor(baseUrl) },
        client = okHttp,
        json = json,
    )

    fun dashboardNotificationSocket(baseUrl: String): DashboardNotificationSocket =
        DashboardNotificationSocket(
            baseUrl = baseUrl,
            tokenProvider = { securePrefs.tokenFor(baseUrl) },
            client = okHttp,
            json = json,
        )

    // ------------------------------------------------------------ 仓储装配

    fun instanceRepository(baseUrl: String): InstanceRepository = InstanceRepository(
        baseUrl = baseUrl,
        api = api(baseUrl),
        securePrefs = securePrefs,
        instanceDao = database.instances(),
        socketFactory = { chatSocket(baseUrl, it) },
    )

    fun threadRepository(baseUrl: String, agentId: String): ThreadRepository =
        ThreadRepository(agentId = agentId, api = api(baseUrl), threadDao = database.threads())

    fun chatRepository(baseUrl: String, agentId: String, threadId: String): ChatRepository =
        ChatRepository(
            agentId = agentId,
            threadId = threadId,
            baseUrl = baseUrl,
            api = api(baseUrl),
            socket = chatSocket(baseUrl, agentId),
            messageDao = database.messages(),
            threadDao = database.threads(),
            pendingOpDao = database.pendingOps(),
            attachmentDao = database.attachments(),
            attachmentStore = attachments,
            compactMarkerDao = database.compactMarkers(),
            json = json,
        )

    /** 会话标签。**纯本地**，与实例无关（`TagRepository` 顶部说明了为什么没有服务端那一侧）。 */
    fun tagRepository(): TagRepository = TagRepository(database.threadTags())

    /** 结构化记忆。同样是本地优先的一层，与实例无关。 */
    fun memoryRepository(): MemoryRepository = MemoryRepository(database.memoryItems())

    /** 服务端记忆聚合：把画像 / 情绪 / 记忆沉淀拉平成适合移动端的三块。 */
    fun serverMemoryRepository(baseUrl: String): ServerMemoryRepository = ServerMemoryRepository(api(baseUrl))

    /** 自动任务镜像。带实例与 agent —— 服务端 cron 是按 agent 分组的。 */
    fun automationRepository(baseUrl: String, agentId: String): AutomationRepository =
        AutomationRepository(
            agentId = agentId,
            api = api(baseUrl),
            dao = database.automationTasks(),
        )

    fun naturalLanguageTaskRepository(baseUrl: String, agentId: String): NaturalLanguageTaskRepository =
        NaturalLanguageTaskRepository(
            socketFactory = { chatSocket(baseUrl, agentId) },
            temporarySessionKey = {
                "$agentId:dashboard:android-task-${java.util.UUID.randomUUID()}:dm"
            },
            deleteThread = { threadId -> api(baseUrl).deleteThread(agentId, threadId) },
            bindConnectors = { taskId, serverNames ->
                automationRepository(baseUrl, agentId).bindConnectors(taskId, serverNames)
            },
            refreshTasks = { automationRepository(baseUrl, agentId).refresh() },
        )

    fun mobileDataRepository(): MobileDataRepository = MobileDataRepository(database, mobileCipher, json)

    fun todayRepository(baseUrl: String, agentId: String): TodayRepository = TodayRepository(
        agentId = agentId,
        api = api(baseUrl),
        database = database,
        json = json,
    )

    fun interestMonitorRepository(baseUrl: String, agentId: String): InterestMonitorRepository =
        InterestMonitorRepository(agentId, api(baseUrl))

    fun todayGoalRepository(baseUrl: String, agentId: String): TodayGoalRepository =
        TodayGoalRepository(agentId, api(baseUrl))

    fun mobileDeviceId(): String = mobileDevicePrefs.getString("id", null) ?: UUID.randomUUID().toString().also {
        mobileDevicePrefs.edit().putString("id", it).apply()
    }
}
