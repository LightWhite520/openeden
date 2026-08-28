package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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

    @Test
    fun `repair and repeated consistency use exact positive corpus`() = runTest {
        val evaluator = DeterministicRelationshipEventEvaluator()

        assertEquals(
            RelationshipEventType.REPAIR,
            evaluator.evaluate(turn("对不起，我刚才弄错了")).events.single().type,
        )
        assertEquals(
            RelationshipEventType.REPAIR,
            evaluator.evaluate(turn("抱歉，我误会了")).events.single().type,
        )
        assertEquals(
            RelationshipEventType.REPEATED_CONSISTENCY,
            evaluator.evaluate(turn("你一直都记得我们的约定")).events.single().type,
        )
        assertEquals(
            RelationshipEventType.REPEATED_CONSISTENCY,
            evaluator.evaluate(turn("你每次都做到答应的事")).events.single().type,
        )
    }

    @Test
    fun `repair and repeated consistency proposals and negations are excluded`() = runTest {
        val evaluator = DeterministicRelationshipEventEvaluator()

        listOf("要不要说对不起", "你记得吗").forEach { text ->
            assertTrue(evaluator.evaluate(turn(text)).events.isEmpty(), text)
        }
        listOf("我不是在道歉", "我不是每次都这样").forEach { text ->
            assertTrue(evaluator.evaluate(turn(text)).events.isEmpty(), text)
        }
    }

    @Test
    fun `provider failure falls back to deterministic high precision rules`() = runTest {
        val evaluator = FallbackRelationshipEventEvaluator(
            primary = evaluator { error("provider unavailable") },
            fallback = DeterministicRelationshipEventEvaluator(),
        )

        val evaluation = evaluator.evaluate(turn("对不起，我刚才弄错了"))

        assertEquals(RelationshipEventType.REPAIR, evaluation.events.single().type)
    }

    @Test
    fun `provider cancellation is never converted into fallback evaluation`() = runTest {
        var fallbackCalled = false
        val evaluator = FallbackRelationshipEventEvaluator(
            primary = evaluator { throw CancellationException("cancelled") },
            fallback = evaluator {
                fallbackCalled = true
                RelationshipEvaluation(emptyList(), confidence = 1.0f)
            },
        )

        assertFailsWith<CancellationException> { evaluator.evaluate(turn("对不起，我刚才弄错了")) }
        assertFalse(fallbackCalled)
    }

    @Test
    fun `non exception failures propagate without fallback evaluation`() = runTest {
        var fallbackCalled = false
        val evaluator = FallbackRelationshipEventEvaluator(
            primary = evaluator { throw AssertionError("fatal") },
            fallback = evaluator {
                fallbackCalled = true
                RelationshipEvaluation(emptyList(), confidence = 1.0f)
            },
        )

        assertFailsWith<AssertionError> { evaluator.evaluate(turn("对不起，我刚才弄错了")) }
        assertFalse(fallbackCalled)
    }

    private fun evaluator(
        block: suspend (RelationshipTurn) -> RelationshipEvaluation,
    ): RelationshipEventEvaluator = object : RelationshipEventEvaluator {
        override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation = block(turn)
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
