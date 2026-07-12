package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.runtime.DirectInferenceExecutor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RebuildableVectorIndexTest {
    @Test
    fun `index supports incremental updates and full rebuild`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        val first = entry("first", "alpha")
        val second = entry("second", "beta")

        index.insert(first)
        index.insert(second)
        assertEquals(listOf("first"), ids(index.search(query("alpha"))))

        index.remove("first")
        assertTrue(index.search(query("alpha")).none { it.entry.id == "first" })
        index.rebuild(listOf(first, second), batchSize = 1)
        assertFalse(index.isDirty)
        assertEquals(listOf("first"), ids(index.search(query("alpha"))))
    }

    private fun query(text: String): VectorSearchRequest = VectorSearchRequest(
        sessionId = "CLI:u1",
        semanticEmbedding = if (text == "alpha") listOf(1.0f, 0.0f) else listOf(0.0f, 1.0f),
        room = null,
        kind = null,
        limit = 1,
    )

    private fun ids(hits: List<VectorSearchHit>): List<String> = hits.map { it.entry.id }

    private fun entry(id: String, content: String): MemoryEntry = MemoryEntry(
        id = id,
        sessionId = "CLI:u1",
        content = content,
        room = MemoryRoom.EVENT_ROOM,
        kind = MemoryKind.RAW,
        semanticEmbedding = if (content == "alpha") listOf(1.0f, 0.0f) else listOf(0.0f, 1.0f),
        emotionalEmbedding = BioVector.Neutral.toList(),
        metadata = MemoryMetadata(
            snapshot8D = BioVector.Neutral,
            omegaState = 0.0f,
            deltaVec = io.openeden.bio.VectorDelta.Zero,
            snapshotOrigin = BioVector.Neutral,
            userId = "u1",
        ),
    )
}
