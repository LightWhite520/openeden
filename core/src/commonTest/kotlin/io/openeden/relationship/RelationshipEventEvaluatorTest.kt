package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RelationshipEventEvaluatorTest {
    @Test
    fun `proposal phrases are never boundary requests`() = runTest {
        val evaluator = DeterministicRelationshipEventEvaluator()

        listOf("要不要吃饭", "你要不要抱我", "我不是不要你").forEach { text ->
            assertFalse(evaluator.evaluate(turn(text)).events.any { it.type == RelationshipEventType.BOUNDARY_REQUEST })
        }

        assertTrue(evaluator.evaluate(turn("不要这样，请停下")).events.any { it.type == RelationshipEventType.BOUNDARY_REQUEST })
    }

    @Test
    fun `committable events require confidence threshold`() {
        val event = RelationshipEvent(
            eventId = "event-1",
            incarnationId = "incarnation-1",
            canonicalSubjectId = "QQ:user-1",
            sourceTurnId = "turn-1",
            type = RelationshipEventType.BOUNDARY_REQUEST,
            confidence = 0.74f,
            evidenceDigest = "boundary request",
            createdAtMs = 1L,
        )

        assertTrue(RelationshipEvaluation(listOf(event), confidence = 0.74f).committableEvents.isEmpty())
        assertEquals(listOf(event), RelationshipEvaluation(listOf(event), confidence = 0.75f).committableEvents)
    }

    private fun turn(userText: String): RelationshipTurn = RelationshipTurn(
        sourceTurnId = "turn-1",
        incarnationId = "incarnation-1",
        subjectId = "QQ:user-1",
        userText = userText,
        assistantText = "我在听。",
        completedAtMs = 1L,
    )
}
