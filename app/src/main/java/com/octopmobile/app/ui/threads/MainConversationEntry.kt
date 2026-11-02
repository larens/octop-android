package com.octopmobile.app.ui.threads

import com.octopmobile.app.data.db.MainConversationEntity
import com.octopmobile.app.data.remote.ApiError
import com.octopmobile.app.data.remote.ApiResult

/** Result of resolving the stable entry target for the local "Chats" tab. */
internal sealed interface MainConversationResolution {
    data class Ready(
        val threadId: String,
        val fresh: Boolean,
    ) : MainConversationResolution

    data class Failed(val error: ApiError) : MainConversationResolution
}

/**
 * Resolve the dashboard thread behind a stable main conversation.
 *
 * A brand-new user may not have a dashboard Thread bound yet. In that case we open one local
 * draft instead of eagerly creating an empty server Thread; the existing send path creates and
 * binds the real Thread on the first message.
 */
internal suspend fun resolveMainConversationEntry(
    refreshConversation: suspend () -> ApiResult<MainConversationEntity>,
    createDraft: suspend () -> String,
): MainConversationResolution =
    when (val result = refreshConversation()) {
        is ApiResult.Failure -> MainConversationResolution.Failed(result.error)
        is ApiResult.Ok -> {
            val threadId = result.value.routerThreadId?.trim().orEmpty()
            if (threadId.isNotEmpty()) {
                MainConversationResolution.Ready(threadId = threadId, fresh = false)
            } else {
                MainConversationResolution.Ready(threadId = createDraft(), fresh = true)
            }
        }
    }
