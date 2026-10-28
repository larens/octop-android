package com.octopmobile.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPayloadTest {

    @Test
    fun `ask user question is parsed into selectable questions`() {
        val pending = parseHitlPending(
            rawHitl(
                """
                {
                  "questions": [{
                    "id": "model",
                    "header": "Choose a model",
                    "question": "Which model should I use?",
                    "multi_select": false,
                    "options": [
                      {"label": "Fast (Recommended)", "description": "Lower latency."},
                      {"label": "Deep", "description": "More reasoning."}
                    ]
                  }]
                }
                """,
            ),
        )

        assertNotNull(pending)
        assertTrue(pending!!.isQuestion)
        assertTrue(pending.canResume)
        assertEquals(1, pending.questions.size)
        val question = pending.questions.single()
        assertEquals("model", question.id)
        assertEquals("Choose a model", question.header)
        assertEquals(2, question.options.size)
        assertFalse(question.multiSelect)
        assertEquals("Lower latency.", question.options.first().description)
    }

    @Test
    fun `stringified questions payload is accepted`() {
        val pending = parseHitlPending(
            rawHitl(
                """
                {"questions":"[{\"id\":\"scope\",\"question\":\"Which scope?\",\"multi_select\":true}]"}
                """,
            ),
        )

        assertTrue(pending!!.questions.single().multiSelect)
        assertTrue(pending.questions.single().options.isEmpty())
    }

    @Test
    fun `answers retain question and option mapping`() {
        val questions = listOf(
            HitlQuestion(
                id = "model",
                header = "Model",
                question = "Which model?",
                options = emptyList(),
                multiSelect = false,
            ),
            HitlQuestion(
                id = "features",
                header = "Features",
                question = "Which features?",
                options = emptyList(),
                multiSelect = true,
            ),
        )
        val message = formatQuestionAnswers(
            questions,
            mapOf(
                "model" to HitlQuestionAnswer(selectedLabels = listOf("Fast")),
                "features" to HitlQuestionAnswer(
                    selectedLabels = listOf("Search", "Memory"),
                    custom = "Also keep export",
                ),
            ),
        )

        assertEquals(
            "- Model: Fast\n- Features: Search、Memory；补充：Also keep export",
            message,
        )
    }

    @Test
    fun `historical question tool call is available without resumable hitl`() {
        val pending = hitlQuestionFromToolCall(
            name = ASK_USER_TOOL,
            args = """{"questions":[{"question":"Which route?","options":[{"label":"A"}]}]}""",
        )

        assertNotNull(pending)
        assertFalse(pending!!.canResume)
        assertEquals("Which route?", pending.questions.single().question)
    }

    private fun rawHitl(args: String): JsonObject = Json.parseToJsonElement(
        """
        {
          "pending_id": "pending-1",
          "action_requests": [{
            "name": "ask_user_question",
            "args": $args
          }]
        }
        """.trimIndent(),
    ) as JsonObject
}
