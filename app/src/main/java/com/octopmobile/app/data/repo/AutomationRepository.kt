package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.AutomationTaskDao
import com.octopmobile.app.data.db.AutomationTaskEntity
import com.octopmobile.app.data.db.AutomationTaskStatus
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.CronJobCreateBody
import com.octopmobile.app.data.remote.dto.CronJobDto
import com.octopmobile.app.data.remote.dto.CronJobPatchBody
import kotlinx.coroutines.flow.Flow

/**
 * 自动任务（服务端 cron，docs/protocol.md §5.5）。
 *
 * **真值在服务端**：真正按点触发的是服务端 APScheduler，本地这张表只是镜像，供离线可读
 * 与就地编辑。所以这里的写路径全部沿用会话列表那套「**服务端先成功、再写本地**」——
 * 不做乐观更新，否则会出现「界面显示已暂停、服务端还在跑」这种最糟的错位。
 *
 * 列表接口 `GET /agents/{id}/cron` 是**全量**的（裸数组），所以 [refresh] 直接整批替换，
 * 用 `deleteMissing` 收掉服务端已经删掉的行；不需要增量对齐。
 *
 * 客户端不实现任何本地调度（没有 WorkManager、没有 AlarmManager）——「下次执行时间」只是为了
 * 让列表上有个能看的字段，由 [ScheduleRules] 按设备时区**估算**（见该文件顶部的说明）。
 */
class AutomationRepository(
    private val agentId: String,
    private val api: OctopApi,
    private val dao: AutomationTaskDao,
) {

    fun observeTasks(): Flow<List<AutomationTaskEntity>> = dao.observeByAgent(agentId)

    /**
     * 拉取全量 cron 并整体覆盖本地镜像。返回落库条数，供 UI 区分「没有任务」与「拉取失败」。
     *
     * `createdAt` 要延续本地那份：服务端没有创建时间字段（见 `CronJobDto`），
     * 每次刷新都重记一次的话，列表排序会跟着乱跳。
     */
    suspend fun refresh(): ApiResult<Int> = when (val result = api.cronJobs(agentId)) {
        is ApiResult.Ok -> {
            val now = System.currentTimeMillis()
            val known = dao.listByAgent(agentId).associateBy { it.id }
            val entities = result.value.map { dto ->
                dto.toEntity(
                    agentId = agentId,
                    createdAt = known[dto.id]?.createdAt ?: now,
                    now = now,
                )
            }
            dao.upsertAll(entities)
            // 服务端一条都没返回时 `NOT IN ()` 是非法 SQL，直接整表清掉这个 agent 的镜像。
            val keep = entities.map { it.id }
            if (keep.isEmpty()) dao.deleteByAgent(agentId) else dao.deleteMissing(agentId, keep)
            ApiResult.Ok(entities.size)
        }

        is ApiResult.Failure -> result
    }

    /**
     * 新建任务。`trigger` 用服务端原文（如 `cron:0 9 * * *`），不做客户端 DSL —— 服务端
     * 是唯一解析方，多一层翻译只会多一处不一致（docs/protocol.md §5.5 的四种形态）。
     */
    suspend fun create(
        title: String,
        prompt: String,
        scheduleRule: String,
        scopeRef: String? = null,
        mcpServers: List<String>? = null,
    ): ApiResult<Unit> =
        when (
            val result = api.createCronJob(
                agentId,
                CronJobCreateBody(
                    name = title.trim().takeIf { it.isNotEmpty() },
                    trigger = scheduleRule.trim(),
                    prompt = prompt.trim(),
                    sessionKey = scopeRef?.trim()?.takeIf { it.isNotEmpty() },
                    enabled = true,
                    mcpServers = mcpServers,
                ),
            )
        ) {
            is ApiResult.Ok -> {
                cache(result.value)
                ApiResult.Ok(Unit)
            }

            is ApiResult.Failure -> result
        }

    /** 编辑名称 / 规则 / 提示词。`enabled` 不走这里 —— 启停是 [setEnabled]，两件事分开。 */
    suspend fun update(
        id: String,
        title: String,
        prompt: String,
        scheduleRule: String,
    ): ApiResult<Unit> =
        when (
            val result = api.patchCronJob(
                agentId,
                id,
                CronJobPatchBody(
                    name = title.trim().takeIf { it.isNotEmpty() },
                    trigger = scheduleRule.trim(),
                    prompt = prompt.trim(),
                ),
            )
        ) {
            is ApiResult.Ok -> {
                cache(result.value)
                ApiResult.Ok(Unit)
            }

            is ApiResult.Failure -> result
        }

    /** Natural-language recommendations create first, then bind the matched connector tools here. */
    suspend fun bindConnectors(id: String, mcpServers: List<String>): ApiResult<Unit> =
        when (val result = api.patchCronJob(agentId, id, CronJobPatchBody(mcpServers = mcpServers))) {
            is ApiResult.Ok -> {
                cache(result.value)
                ApiResult.Ok(Unit)
            }
            is ApiResult.Failure -> result
        }

    /**
     * 暂停 / 继续。上游用 `exclude_unset` 判断「该改哪些字段」，而 `CronJobPatchBody` 里
     * 只有 [CronJobPatchBody.enabled] 被填，其余是 null 且不进 JSON —— 正好只改这一个字段。
     */
    suspend fun setEnabled(id: String, enabled: Boolean): ApiResult<Unit> =
        when (val result = api.patchCronJob(agentId, id, CronJobPatchBody(enabled = enabled))) {
            is ApiResult.Ok -> {
                // 服务端不回 next_run_at，暂停后本地那条「下次」要自己抹掉。
                cache(result.value)
                ApiResult.Ok(Unit)
            }

            is ApiResult.Failure -> result
        }

    suspend fun delete(id: String): ApiResult<Unit> =
        when (val result = api.deleteCronJob(agentId, id)) {
            is ApiResult.Ok -> {
                dao.delete(id)
                result
            }

            is ApiResult.Failure -> result
        }

    /**
     * 立刻跑一次（204）。**不影响原有排期** —— 服务端在 APScheduler 之外单独投递一次。
     *
     * 本次执行会改服务端的 `last_run_at` / `last_status`，但那是异步发生的，所以这里不立刻
     * 回读；界面上的「上次执行」会在下一次 [refresh] 时对上。
     */
    suspend fun runNow(id: String): ApiResult<Unit> = api.runCronNow(agentId, id)

    suspend fun clear() {
        dao.deleteByAgent(agentId)
    }

    /** 写单条：接口返回的对象就是最新真值，直接按当前时间重算显示字段落库。 */
    private suspend fun cache(dto: CronJobDto) {
        val now = System.currentTimeMillis()
        val createdAt = dao.listByAgent(agentId).firstOrNull { it.id == dto.id }?.createdAt ?: now
        dao.upsert(dto.toEntity(agentId = agentId, createdAt = createdAt, now = now))
    }
}

/**
 * 服务端 cron → 本地镜像。
 *
 * - `enabled` → [AutomationTaskStatus]；暂停的任务不给「下次执行」；
 * - `last_run_at` 是 epoch **秒**，本地统一毫秒（docs/spec.md §8.2）；
 * - [AutomationTaskEntity.title] 允许为空名：服务端 `name` 是可选的，退到 prompt 首行，
 *   再退到 id，免得列表上出现一行空白。
 */
private fun CronJobDto.toEntity(
    agentId: String,
    createdAt: Long,
    now: Long,
): AutomationTaskEntity {
    val lastRunMillis = lastRunAt?.let { it * 1000 }
    val displayTitle = name?.takeIf { it.isNotBlank() }
        ?: prompt.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?.take(TITLE_FALLBACK_LIMIT)
        ?: id
    return AutomationTaskEntity(
        id = id,
        agentId = agentId,
        title = displayTitle,
        prompt = prompt,
        scheduleRule = trigger,
        scopeRef = sessionKey,
        status = if (enabled) AutomationTaskStatus.ACTIVE else AutomationTaskStatus.PAUSED,
        lastRunAt = lastRunMillis,
        lastStatus = lastStatus,
        lastError = lastError,
        nextRunAt = if (enabled) {
            ScheduleRules.nextRunAt(ScheduleRules.parse(trigger), now, lastRunMillis)
        } else {
            null
        },
        createdAt = createdAt,
    )
}

/** 用 prompt 首行当标题时的截断长度。 */
private const val TITLE_FALLBACK_LIMIT = 60
