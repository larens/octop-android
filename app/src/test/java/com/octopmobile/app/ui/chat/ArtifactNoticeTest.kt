package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.remote.ApiError
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactNoticeTest {
    @Test
    fun `missing artifact uses a specific notice`() {
        val notice = artifactFailureNotice(
            ApiError.Http(status = 404, code = "NOT_FOUND", message = null),
            fallback = ChatNotice.ShareFailed,
        )

        assertEquals(ChatNotice.ArtifactMissing, notice)
    }

    @Test
    fun `other artifact failures keep the operation fallback`() {
        val notice = artifactFailureNotice(
            ApiError.Http(status = 500, code = "INTERNAL_ERROR", message = null),
            fallback = ChatNotice.PreviewFailed,
        )

        assertEquals(ChatNotice.PreviewFailed, notice)
    }
}
