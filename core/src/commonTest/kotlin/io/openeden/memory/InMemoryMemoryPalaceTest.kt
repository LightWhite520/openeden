package io.openeden.memory

import io.openeden.runtime.affect.OmegaState


import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.trace.TraceTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryMemoryPalaceTest {
    @Test
    fun `retrieval returns required metadata and trace tag`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor)
        palace.write(entry(id = "m1", content = "alpha project memory", delta = VectorDelta(p = 0.2f)))

        val result = palace.retrieve(
            RetrievalRequest(
                sessionId = "CLI:u1",
                userInput = "project",
                currentVector = BioVector.Neutral,
                origin = BioVector.Neutral,
                mode = RetrievalMode.CONGRUENT,
            ),
        )

        assertEquals(RetrievalMode.CONGRUENT, result.mode)
        assertContains(result.traceTags, TraceTag.MemoryRetrieved)
        assertEquals("user-1", result.memories.single().metadata.userId)
        assertEquals(VectorDelta(p = 0.2f), result.memories.single().metadata.deltaVec)
    }

    @Test
    fun `mixed retrieval includes positive skew memories`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 2)
        palace.write(entry(id = "sad", content = "same text", vector = BioVector.Neutral.copy(p = 0.1f, v = 0.1f)))
        palace.write(entry(id = "calm", content = "same text", vector = BioVector.Neutral.copy(p = 0.9f, v = 0.9f)))

        val result = palace.retrieve(
            RetrievalRequest(
                sessionId = "CLI:u1",
                userInput = "same",
                currentVector = BioVector.Neutral.copy(p = 0.1f, v = 0.1f),
                origin = BioVector.Neutral,
                mode = RetrievalMode.MIXED,
            ),
        )

        assertEquals(setOf("sad", "calm"), result.memories.map { it.id }.toSet())
    }

    @Test
    fun `contrast retrieval uses center symmetric emotional target`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 1)
        palace.write(entry(id = "collapse", content = "same", vector = BioVector.Neutral.copy(p = 0.1f, v = 0.1f)))
        palace.write(entry(id = "opposite", content = "same", vector = BioVector.Neutral.copy(p = 0.9f, v = 0.9f)))

        val result = palace.retrieve(
            RetrievalRequest(
                sessionId = "CLI:u1",
                userInput = "same",
                currentVector = BioVector.Neutral.copy(p = 0.1f, v = 0.1f),
                origin = BioVector.Neutral,
                mode = RetrievalMode.CONTRAST,
            ),
        )

        assertEquals("opposite", result.memories.single().id)
    }

    @Test
    fun `momentum weight promotes high impact memories`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 1)
        palace.write(entry(id = "low", content = "same", delta = VectorDelta.Zero))
        palace.write(entry(id = "high", content = "same", delta = VectorDelta(p = 0.8f, v = 0.2f)))

        val result = palace.retrieve(
            RetrievalRequest(
                sessionId = "CLI:u1",
                userInput = "same",
                currentVector = BioVector.Neutral,
                origin = BioVector.Neutral,
                mode = RetrievalMode.CONGRUENT,
            ),
        )

        assertEquals("high", result.memories.single().id)
        assertTrue(result.memories.single().score > 0.0f)
    }

    private fun entry(
        id: String,
        content: String,
        vector: BioVector = BioVector.Neutral,
        delta: VectorDelta = VectorDelta.Zero,
        tags: Set<String> = emptySet(),
    ): MemoryEntry {
        val metadata = MemoryMetadata(
            snapshot8D = vector,
            omegaState = 0.1f,
            deltaVec = delta,
            snapshotOrigin = BioVector.Neutral,
            userId = "user-1",
        )
        return MemoryEntry(
            id = id,
            sessionId = "CLI:u1",
            content = content,
            room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW,
            tags = tags,
            semanticEmbedding = InMemoryMemoryPalace.embedText(content),
            emotionalEmbedding = InMemoryMemoryPalace.embedVector(vector),
            metadata = metadata,
        )
    }
}
