package com.octopmobile.app.ui.threads

import com.octopmobile.app.data.db.MainConversationEntity
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainConversationEntryTest {
    @Test
    fun boundRouterThreadIsReusedWithoutCreatingDraft() = runBlocking {
        var drafts = 0

        val result = resolveMainConversationEntry(
            refreshConversation = { ApiResult.Ok(conversation("thread-main")) },
            createDraft = {
                drafts += 1
                "draft-unexpected"
            },
        )

        assertEquals(MainConversationResolution.Ready("thread-main", fresh = false), result)
        assertEquals(0, drafts)
    }

    @Test
    fun missingRouterThreadFallsBackToOneLocalDraft() = runBlocking {
        var drafts = 0

        val result = resolveMainConversationEntry(
            refreshConversation = { ApiResult.Ok(conversation(routerThreadId = null)) },
            createDraft = {
                drafts += 1
                "draft-1"
            },
        )

        assertEquals(MainConversationResolution.Ready("draft-1", fresh = true), result)
        assertEquals(1, drafts)
    }

    @Test
    fun conversationFailureDoesNotCreateDraft() = runBlocking {
        var drafts = 0
        val error = ApiError.Network("offline")

        val result = resolveMainConversationEntry(
            refreshConversation = { ApiResult.Failure(error) },
            createDraft = {
                drafts += 1
                "draft-unexpected"
            },
        )

        assertTrue(result is MainConversationResolution.Failed)
        assertEquals(error, (result as MainConversationResolution.Failed).error)
        assertEquals(0, drafts)
    }

    private fun conversation(routerThreadId: String?) = MainConversationEntity(
        conversationId = "conv-1",
        agentId = "agent-1",
        userId = 1,
        routerThreadId = routerThreadId,
        stateJson = "{}",
        summary = "",
        lastConversationSeq = 0,
        createdAt = 1,
        updatedAt = 1,
        archivedAt = null,
    )
}
