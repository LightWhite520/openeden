package io.openeden.runtime.state

import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionStateStore

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

class HomeostasisCentroidProviderTest {
    @Test
    fun `bootstrap centroid provider returns persisted origin`() = runTest {
        val store = MutableSessionStateStore()
        val origin = BioVector.Neutral.copy(p = 0.35f, v = 0.4f)
        store.write(SessionStateStore.neutral("QQ:centroid").copy(origin = origin))
        val provider = StoredOriginCentroidProvider(store)

        assertEquals(origin, provider.centroidFor("QQ:centroid"))
    }

    @Test
    fun `sliding window centroid averages stable daily memory vectors`() = runTest {
        val store = MutableSessionStateStore()
        val memory = InMemoryMemoryPalace(DirectInferenceExecutor)
        val provider = SlidingWindowHomeostasisCentroidProvider(
            memoryStore = memory,
            fallback = StoredOriginCentroidProvider(store),
            windowSize = 4,
        )
        memory.write(stableEntry("a", BioVector.Neutral.copy(p = 0.2f, v = 0.4f)))
        memory.write(stableEntry("b", BioVector.Neutral.copy(p = 0.6f, v = 0.8f)))

        val centroid = provider.centroidFor("QQ:centroid")

        assertEquals(0.4f, centroid.p, 0.0001f)
        assertEquals(0.6f, centroid.v, 0.0001f)
    }

    private fun stableEntry(id: String, vector: BioVector): MemoryEntry =
        MemoryEntry(
            id = id,
            sessionId = "QQ:centroid",
            content = "stable daily",
            room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW,
            tags = setOf("daily", "stable"),
            semanticEmbedding = InMemoryMemoryPalace.embedText("stable daily"),
            emotionalEmbedding = InMemoryMemoryPalace.embedVector(vector),
            metadata = MemoryMetadata(
                snapshot8D = vector,
                omegaState = 0.1f,
                deltaVec = VectorDelta.Zero,
                snapshotOrigin = BioVector.Neutral,
                userId = "user",
            ),
        )
}
