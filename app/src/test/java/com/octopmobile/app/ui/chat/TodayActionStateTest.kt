package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.remote.dto.TodayCardActionState
import com.octopmobile.app.data.ws.ServerEvent
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayActionStateTest {
    @Test
    fun realTurnEventsDriveActionState() {
        assertEquals(
            TodayCardActionState.RUNNING,
            todayActionState(ServerEvent.TurnStarted("turn.started", "thread-1", "turn-1", 1, null)),
        )
        assertEquals(
            TodayCardActionState.WAITING_CONFIRMATION,
            todayActionState(ServerEvent.HitlRequired("thread-1", "pending-1", buildJsonObject {})),
        )
        assertEquals(
            TodayCardActionState.COMPLETED,
            todayActionState(ServerEvent.TurnEnded("thread-1", "turn-1", false, false)),
        )
        assertEquals(
            TodayCardActionState.FAILED,
            todayActionState(ServerEvent.TurnEnded("thread-1", "turn-1", true, false)),
        )
        assertNull(todayActionState(ServerEvent.TurnStatus("thread-1", false)))
    }
}
