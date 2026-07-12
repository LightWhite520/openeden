package io.openeden.relationship

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeterministicUserAffectAnalyzerTest {
    private val analyzer = DeterministicUserAffectAnalyzer()

    @Test
    fun `blank input returns explicit unknown`() = runTest {
        assertEquals(UserAffectState.Uncertain, analyzer.analyze("   "))
    }

    @Test
    fun `supported Chinese affect words produce confidence bearing state`() = runTest {
        val result = analyzer.analyze("我很难过和孤独，想找你聊聊")

        assertTrue(result.confidence >= 0.5f)
        assertTrue(result.valence < 0.5f)
        assertTrue(result.connectionNeed > 0.5f)
        assertTrue(result.arousal > 0.5f)
    }

    @Test
    fun `unsupported text remains low confidence and finite`() = runTest {
        val result = analyzer.analyze("今天是星期三")

        assertTrue(result.confidence < 0.5f)
        assertTrue(listOf(result.valence, result.arousal, result.dominance, result.connectionNeed, result.openness).all(Float::isFinite))
    }
}
