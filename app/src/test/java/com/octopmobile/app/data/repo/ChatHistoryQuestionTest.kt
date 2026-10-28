package com.octopmobile.app.data.repo

import com.octopmobile.app.data.model.ASK_USER_TOOL
import com.octopmobile.app.data.remote.dto.BlockType
import com.octopmobile.app.data.remote.dto.ContentBlockDto
import com.octopmobile.app.data.remote.dto.MessageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryQuestionTest {

    @Test
    fun `unfinished question is restored from latest turn`() {
        val pending = orphanQuestionFromHistory(
            listOf(
                MessageDto(
                    role = "user",
                    content = listOf(ContentBlockDto(type = BlockType.TEXT, text = "Start")),
                ),
                MessageDto(
                    role = "assistant",
                    content = listOf(questionToolCall()),
                ),
                MessageDto(
                    role = "assistant",
                    content = listOf(
                        ContentBlockDto(type = BlockType.THINKING, thinking = "Waiting for an answer"),
                    ),
                ),
            ),
        )

        assertNotNull(pending)
        assertFalse(pending!!.canResume)
        assertEquals("米家这条接入路径，你想走哪条？", pending.questions.single().question)
    }

    @Test
    fun `answered question is not restored`() {
        val pending = orphanQuestionFromHistory(
            listOf(
                MessageDto(role = "assistant", content = listOf(questionToolCall())),
                MessageDto(
                    role = "tool",
                    content = listOf(
                        ContentBlockDto(
                            type = BlockType.TOOL_RESULT,
                            id = "question-call",
                            name = ASK_USER_TOOL,
                            output = "A",
                        ),
                    ),
                ),
            ),
        )

        assertNull(pending)
    }

    @Test
    fun `question from an older turn is ignored`() {
        val pending = orphanQuestionFromHistory(
            listOf(
                MessageDto(role = "assistant", content = listOf(questionToolCall())),
                MessageDto(
                    role = "user",
                    content = listOf(ContentBlockDto(type = BlockType.TEXT, text = "Never mind")),
                ),
            ),
        )

        assertNull(pending)
    }

    private fun questionToolCall(): ContentBlockDto = ContentBlockDto(
        type = BlockType.TOOL_USE,
        id = "question-call",
        name = ASK_USER_TOOL,
        input = Json.parseToJsonElement(
            """
            {
              "questions": [{
                "question": "米家这条接入路径，你想走哪条？",
                "options": [{"label": "A"}, {"label": "B"}]
              }]
            }
            """.trimIndent(),
        ),
    )
}
