package com.octopmobile.app.data.model

import com.octopmobile.app.data.remote.dto.HistoryResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatArtifactTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `history response keeps structured artifact refs and legacy paths`() {
        val response = json.decodeFromString<HistoryResponse>(
            """
            {
              "thread_id": "thread-1",
              "artifacts": ["outbound/old.md"],
              "artifact_refs": [
                {"path": "outbound/report.html", "agent_id": "AGT-EXPERT"},
                {"path": "outbound/notes.md"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("AGT-EXPERT", response.artifactRefs.first().agentId)
        assertEquals("outbound/old.md", response.artifacts.single())
    }

    @Test
    fun `artifacts fall back to the thread agent and deduplicate paths`() {
        val artifacts = chatArtifacts(
            defaultAgentId = "AGT-HOME",
            references = listOf(
                ArtifactReference("outbound/report.html", "AGT-EXPERT"),
                ArtifactReference("outbound/report.html", "AGT-EXPERT"),
                ArtifactReference("outbound/notes.md"),
            ),
        )

        assertEquals(2, artifacts.size)
        assertEquals("AGT-EXPERT", artifacts[0].agentId)
        assertEquals("report.html", artifacts[0].filename)
        assertEquals("text/html", artifacts[0].mimeType)
        assertTrue(artifacts[0].isHtml)
        assertEquals("AGT-HOME", artifacts[1].agentId)
        assertEquals("text/markdown", artifacts[1].mimeType)
        assertFalse(artifacts[1].isHtml)
        assertTrue(artifacts[1].isMarkdown)
        assertEquals(ArtifactPreviewKind.HTML, artifacts[0].previewKind)
        assertEquals(ArtifactPreviewKind.MARKDOWN, artifacts[1].previewKind)
    }

    @Test
    fun `non text artifacts keep the external preview path`() {
        val artifact = chatArtifacts(
            defaultAgentId = "AGT-HOME",
            references = listOf(ArtifactReference("outbound/report.pdf")),
        ).single()

        assertFalse(artifact.isHtml)
        assertFalse(artifact.isMarkdown)
        assertEquals(ArtifactPreviewKind.EXTERNAL, artifact.previewKind)
    }

    @Test
    fun `artifact download reference is authenticated workspace URL and path encoded`() {
        val artifact = chatArtifacts(
            defaultAgentId = "AGT-HOME",
            references = listOf(ArtifactReference("outbound/a b.md")),
        ).single()

        assertEquals(
            "/api/agents/AGT-HOME/workspace/download?path=outbound%2Fa+b.md",
            artifact.downloadRef,
        )
    }

    @Test
    fun `download file name removes separators and keeps extension`() {
        val name = artifactDownloadName("../bad:name?.md", timestamp = 0)

        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('?'))
        assertTrue(name.endsWith(".md"))
    }
}
