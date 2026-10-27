package com.octopmobile.app.data.repo

import androidx.room.withTransaction
import com.octopmobile.app.data.db.OctopDatabase
import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.TodayCardDto
import com.octopmobile.app.data.remote.dto.TodayCardActionBody
import com.octopmobile.app.data.remote.dto.TodayCardPatchBody
import com.octopmobile.app.data.remote.dto.TodayCardFeedbackBody
import com.octopmobile.app.data.remote.dto.TodaySourceCoverageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 服务端 Today 卡是真值；Room 只保存最近一次成功同步的离线镜像。 */
class TodayRepository(
    private val agentId: String,
    private val api: OctopApi,
    private val database: OctopDatabase,
    private val json: Json,
) {
    private val dao = database.todayCards()

    fun observeCards(now: Long = System.currentTimeMillis()): Flow<List<TodayCardEntity>> =
        dao.observeVisible(agentId, now)

    suspend fun refresh(): ApiResult<Int> = when (val result = api.todayCards(agentId)) {
        is ApiResult.Failure -> result
        is ApiResult.Ok -> {
            val cards = result.value.map(::toEntity)
            database.withTransaction {
                dao.upsertAll(cards)
                val keep = cards.map { it.id }
                if (keep.isEmpty()) dao.deleteByAgent(agentId) else dao.deleteMissing(agentId, keep)
            }
            ApiResult.Ok(cards.size)
        }
    }

    suspend fun refreshCoverage(): ApiResult<List<TodaySourceCoverageDto>> =
        when (val result = api.todayCoverage(agentId)) {
            is ApiResult.Failure -> result
            is ApiResult.Ok -> ApiResult.Ok(result.value.sources)
        }

    suspend fun setLifecycle(
        cardId: String,
        lifecycle: String,
        snoozedUntilMillis: Long? = null,
    ): ApiResult<Unit> = when (
        val result = api.patchTodayCard(
            agentId,
            cardId,
            TodayCardPatchBody(lifecycle, snoozedUntilMillis?.div(1000)),
        )
    ) {
        is ApiResult.Failure -> result
        is ApiResult.Ok -> {
            dao.upsert(toEntity(result.value))
            ApiResult.Ok(Unit)
        }
    }

    suspend fun sendFeedback(cardId: String, feedbackType: String): ApiResult<Unit> = when (
        val result = api.sendTodayCardFeedback(agentId, cardId, TodayCardFeedbackBody(feedbackType))
    ) {
        is ApiResult.Failure -> result
        is ApiResult.Ok -> {
            dao.upsert(toEntity(result.value))
            ApiResult.Ok(Unit)
        }
    }

    suspend fun setAction(cardId: String, body: TodayCardActionBody): ApiResult<Unit> = when (
        val result = api.patchTodayCardAction(agentId, cardId, body)
    ) {
        is ApiResult.Failure -> result
        is ApiResult.Ok -> {
            dao.upsert(toEntity(result.value))
            ApiResult.Ok(Unit)
        }
    }

    suspend fun reportAction(cardId: String?, threadId: String?, body: TodayCardActionBody) {
        val ids = if (cardId != null) {
            listOf(cardId)
        } else if (threadId != null) {
            dao.activeExecutionCardIds(agentId, threadId)
        } else {
            emptyList()
        }
        ids.forEach { setAction(it, body) }
    }

    private fun toEntity(dto: TodayCardDto) = TodayCardEntity(
        id = dto.cardId,
        agentId = agentId,
        kind = dto.kind,
        title = dto.title,
        summary = dto.summary,
        whyNow = dto.whyNow,
        confidence = dto.confidence,
        riskLevel = dto.riskLevel,
        lifecycle = dto.lifecycle,
        occurredAt = dto.occurredAt.toMillis(),
        snoozedUntil = dto.snoozedUntil?.toMillis(),
        actionPrompt = dto.actionPrompt,
        evidenceJson = json.encodeToString(ListSerializer(com.octopmobile.app.data.remote.dto.TodayEvidenceDto.serializer()), dto.evidence),
        evidenceCount = dto.evidence.size,
        evidenceSources = dto.evidence.map { it.sourceType }.distinct().joinToString(","),
        sourceUrl = dto.evidence.firstNotNullOfOrNull { it.url },
        goalId = dto.goalId,
        goalTitle = dto.goalTitle,
        goalNeedsReview = dto.goalNeedsReview,
        executionThreadId = dto.executionThreadId,
        executionTurnId = dto.executionTurnId,
        actionState = dto.actionState,
        actionError = dto.actionError,
        actionUpdatedAt = dto.actionUpdatedAt?.toMillis(),
        createdAt = dto.createdAt.toMillis(),
        updatedAt = dto.updatedAt.toMillis(),
    )
}

private fun Long.toMillis(): Long = if (this > 100_000_000_000) this else this * 1000
