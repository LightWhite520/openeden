package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryUtilityFilterTest {
    private val config = MemoryUtilityFilterConfig(
        minSemanticSimilarity = 0.2f,
        minEmotionalSimilarity = 0.2f,
        entropyTolerance = 0.1f,
    )

    @Test
    fun `rejects non finite embeddings`() {
        val result = MemoryUtilityFilter.filter(
            candidates = listOf(entry("nan", listOf(Float.NaN, 1.0f), listOf(1.0f, 0.0f))),
            querySemantic = listOf(1.0f, 0.0f),
            queryEmotion = listOf(1.0f, 0.0f),
            baselineEntropy = null,
            config = config,
        )

        assertEquals(0, result.acceptedCount)
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun `rejects candidates below both similarity minimums`() {
        val result = MemoryUtilityFilter.filter(
            candidates = listOf(entry("low", listOf(0.0f, 1.0f), listOf(0.0f, 1.0f))),
            querySemantic = listOf(1.0f, 0.0f),
            queryEmotion = listOf(1.0f, 0.0f),
            baselineEntropy = null,
            config = config,
        )

        assertEquals(0, result.acceptedCount)
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun `disables entropy gate when no baseline exists`() {
        val result = MemoryUtilityFilter.filter(
            candidates = listOf(entry("finite", listOf(1.0f, 1.0f), listOf(1.0f, 1.0f))),
            querySemantic = listOf(1.0f, 1.0f),
            queryEmotion = listOf(1.0f, 1.0f),
            baselineEntropy = null,
            config = config,
        )

        assertEquals(1, result.acceptedCount)
        assertFalse(result.degraded)
    }

    @Test
    fun `rejects entropy above the stable baseline tolerance`() {
        val result = MemoryUtilityFilter.filter(
            candidates = listOf(entry("noisy", listOf(1.0f, 1.0f), listOf(1.0f, 1.0f))),
            querySemantic = listOf(1.0f, 1.0f),
            queryEmotion = listOf(1.0f, 1.0f),
            baselineEntropy = 0.0f,
            config = config,
        )

        assertEquals(0, result.acceptedCount)
        assertEquals(1, result.rejectedCount)
        assertTrue(result.rejectedForEntropy)
    }

    private fun entry(id: String, semantic: List<Float>, emotional: List<Float>): MemoryEntry = MemoryEntry(
        id = id,
        sessionId = "CLI:test",
        content = id,
        room = MemoryRoom.EVENT_ROOM,
        kind = MemoryKind.RAW,
        semanticEmbedding = semantic,
        emotionalEmbedding = emotional,
        metadata = MemoryMetadata(
            snapshot8D = BioVector.Neutral,
            omegaState = 0.1f,
            deltaVec = VectorDelta.Zero,
            snapshotOrigin = BioVector.Neutral,
            userId = "user",
        ),
    )
}
