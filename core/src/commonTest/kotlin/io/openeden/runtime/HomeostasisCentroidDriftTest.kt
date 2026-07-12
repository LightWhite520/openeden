package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HomeostasisCentroidDriftTest {
    @Test
    fun `centroid movement is bounded from persisted origin`() = runTest {
        val store = MutableSessionStateStore()
        val memory = InMemoryMemoryPalace(DirectInferenceExecutor)
        val provider = SlidingWindowHomeostasisCentroidProvider(
            memoryStore = memory,
            fallback = StoredOriginCentroidProvider(store),
            windowSize = 32,
            maxMovementPerDimension = 0.1f,
        )
        memory.write(entry(BioVector.Neutral.copy(p = 1.0f)))

        assertEquals(0.6f, provider.centroidFor("S").p, 0.0001f)
    }

    private fun entry(vector: BioVector) = MemoryEntry(
        id = "centroid:1",
        sessionId = "S",
        content = "stable",
        room = MemoryRoom.EVENT_ROOM,
        kind = MemoryKind.RAW,
        tags = setOf("daily", "stable"),
        semanticEmbedding = InMemoryMemoryPalace.embedText("stable"),
        emotionalEmbedding = vector.toList(),
        metadata = MemoryMetadata(vector, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u"),
    )
}
