package com.octopmobile.app.data.repo

import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.TodayGoalCreateBody
import com.octopmobile.app.data.remote.dto.TodayGoalDto
import com.octopmobile.app.data.remote.dto.TodayGoalPatchBody

class TodayGoalRepository(private val agentId: String, private val api: OctopApi) {
    suspend fun list(): ApiResult<List<TodayGoalDto>> = api.todayGoals(agentId)

    suspend fun create(
        title: String,
        definition: String,
        successRules: String,
        reviewAt: Long?,
    ): ApiResult<TodayGoalDto> = api.createTodayGoal(
        agentId,
        TodayGoalCreateBody(title.trim(), definition.trim(), successRules.trim(), reviewAt),
    )

    suspend fun update(goalId: String, status: String? = null, confirm: Boolean = false, reviewAt: Long? = null) =
        api.patchTodayGoal(agentId, goalId, TodayGoalPatchBody(status, confirm, reviewAt))
}
