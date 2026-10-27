package com.octopmobile.app.data.repo

import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import com.octopmobile.app.data.remote.dto.InterestMonitorCreateBody
import com.octopmobile.app.data.remote.dto.InterestMonitorDto
import com.octopmobile.app.data.remote.dto.InterestMonitorPatchBody
import com.octopmobile.app.data.remote.dto.InterestMonitorRunResponse

class InterestMonitorRepository(
    private val agentId: String,
    private val api: OctopApi,
) {
    suspend fun list(): ApiResult<List<InterestMonitorDto>> = api.interestMonitors(agentId)

    suspend fun create(description: String, schedule: String, goalId: String?): ApiResult<InterestMonitorDto> =
        api.createInterestMonitor(agentId, InterestMonitorCreateBody(description.trim(), schedule, goalId))

    suspend fun setEnabled(monitorId: String, enabled: Boolean): ApiResult<InterestMonitorDto> =
        api.patchInterestMonitor(agentId, monitorId, InterestMonitorPatchBody(enabled = enabled))

    suspend fun run(monitorId: String): ApiResult<InterestMonitorRunResponse> =
        api.runInterestMonitor(agentId, monitorId)

    suspend fun delete(monitorId: String): ApiResult<Unit> = api.deleteInterestMonitor(agentId, monitorId)
}
