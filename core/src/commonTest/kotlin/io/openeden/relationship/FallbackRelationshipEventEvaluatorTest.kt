package io.openeden.relationship

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FallbackRelationshipEventEvaluatorTest {
    private val turn = RelationshipTurn("turn", "incarnation", "subject", "input", "output", 0)

    @Test
    fun `reports primary failure while preserving fallback even if observer fails`() = runTest {
        val failure = IllegalStateException("unavailable")
        var observed: Exception? = null
        val expected = RelationshipEvaluation(emptyList(), 1f)
        val evaluator = FallbackRelationshipEventEvaluator(
            primary = evaluator { throw failure },
            fallback = evaluator { expected },
            onPrimaryFailure = { observed = it; error("observer unavailable") },
        )
        assertEquals(expected, evaluator.evaluate(turn))
        assertSame(failure, observed)
    }

    @Test
    fun `cancellation bypasses observer and fallback`() = runTest {
        val evaluator = FallbackRelationshipEventEvaluator(
            primary = evaluator { throw CancellationException("cancelled") },
            fallback = evaluator { fail("fallback must not run") },
            onPrimaryFailure = { fail("observer must not run") },
        )
        assertFailsWith<CancellationException> { evaluator.evaluate(turn) }
    }

    private fun evaluator(block: suspend () -> RelationshipEvaluation) = object : RelationshipEventEvaluator {
        override suspend fun evaluate(turn: RelationshipTurn) = block()
    }
}
