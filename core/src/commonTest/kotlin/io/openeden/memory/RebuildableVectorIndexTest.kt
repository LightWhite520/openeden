package io.openeden.memory

import io.openeden.runtime.affect.OmegaState

import io.openeden.bio.BioVector
import io.openeden.runtime.inference.DirectInferenceExecutor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RebuildableVectorIndexTest {
    @Test
    fun `index supports incremental updates and full rebuild`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        val first = entry("first", "alpha")
        val second = entry("second", "beta")

        index.insert(first)
        index.insert(second)
        val insertedHit = index.search(query("alpha")).single()
        assertEquals("first", insertedHit.memoryId)
        assertEquals(first, assertNotNull(insertedHit.entry))
        assertEquals(1.0f, insertedHit.semanticSimilarity)
        assertEquals(1.0f, insertedHit.emotionalSimilarity, absoluteTolerance = 0.00001f)

        index.remove("first")
        assertTrue(index.search(query("alpha")).none { it.memoryId == "first" })
        index.rebuild(listOf(first, second), batchSize = 1)
        assertFalse(index.isDirty)
        assertEquals(listOf("first"), ids(index.search(query("alpha"))))
    }

    @Test
    fun `search preserves session room and kind filters`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        index.rebuild(
            listOf(
                entry("same", "alpha"),
                entry("other-session", "alpha", sessionId = "CLI:u2"),
                entry("other-room", "alpha", room = MemoryRoom.PROFILE_ROOM),
                entry("other-kind", "alpha", kind = MemoryKind.NARRATIVE),
            ),
        )

        val hits = index.search(
            query("alpha").copy(
                room = MemoryRoom.EVENT_ROOM,
                kind = MemoryKind.RAW,
            ),
        )

        assertEquals(listOf("same"), ids(hits))
        assertEquals(listOf("CLI:u1"), hits.map { assertNotNull(it.entry).sessionId })
        assertEquals(listOf(MemoryRoom.EVENT_ROOM), hits.map { assertNotNull(it.entry).room })
        assertEquals(listOf(MemoryKind.RAW), hits.map { assertNotNull(it.entry).kind })
    }

    @Test
    fun `search excludes unauthorized candidates before applying capacity`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        index.rebuild(
            listOf(
                entry(
                    id = "private-other",
                    content = "alpha",
                    incarnationId = "incarnation-1",
                    visibility = MemoryVisibility.PrivateSubject("QQ:other"),
                ),
                entry(
                    id = "scope-shared",
                    content = "beta",
                    incarnationId = "incarnation-1",
                    visibility = MemoryVisibility.ScopeShared("CLI:u1"),
                ),
                entry(
                    id = "incarnation-shared",
                    content = "beta",
                    incarnationId = "incarnation-1",
                    visibility = MemoryVisibility.IncarnationShared,
                ),
            ),
        )

        val hits = index.search(query("alpha").copy(limit = 2, incarnationId = "incarnation-1"))

        assertEquals(listOf("scope-shared", "incarnation-shared"), ids(hits))
    }

    @Test
    fun `same session search never crosses incarnations`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        index.rebuild(
            listOf(
                entry("incarnation-one", "alpha", incarnationId = "incarnation-1"),
                entry("incarnation-two", "alpha", incarnationId = "incarnation-2"),
            ),
        )

        assertEquals(
            listOf("incarnation-one"),
            ids(index.search(query("alpha").copy(incarnationId = "incarnation-1"))),
        )
    }

    @Test
    fun `operator only search requires explicit request authorization`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        index.insert(
            entry(
                id = "operator",
                content = "alpha",
                incarnationId = "incarnation-1",
                visibility = MemoryVisibility.OperatorOnly,
            ),
        )

        val request = query("alpha").copy(incarnationId = "incarnation-1")
        assertTrue(index.search(request).isEmpty())
        assertEquals(listOf("operator"), ids(index.search(request.copy(operatorAuthorized = true))))
    }

    @Test
    fun `search returns exact top k with insertion ordered ties`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        index.rebuild(
            listOf(
                entry("low", "low", semanticEmbedding = listOf(0.0f, 1.0f)),
                entry("tie-first", "tie", semanticEmbedding = listOf(0.8f, 0.6f)),
                entry("best", "best", semanticEmbedding = listOf(1.0f, 0.0f)),
                entry("tie-second", "tie", semanticEmbedding = listOf(0.8f, 0.6f)),
                entry("worst", "worst", semanticEmbedding = listOf(-1.0f, 0.0f)),
            ),
        )

        val hits = index.search(query("alpha").copy(limit = 3))

        assertEquals(listOf("best", "tie-first", "tie-second"), ids(hits))
        assertEquals(listOf(1.0f, 0.8f, 0.8f), hits.map { it.semanticSimilarity })
    }

    @Test
    fun `published rebuild view remains stable across later writes`() = runTest {
        val index = RebuildableInMemoryVectorIndex(DirectInferenceExecutor)
        index.insert(entry("first", "alpha"))
        val published = index.entriesViewForRebuild()

        index.insert(entry("second", "beta"))

        assertEquals(listOf("first"), published.map { it.id })
        assertEquals(listOf("first", "second"), index.entriesViewForRebuild().map { it.id })
    }

    private fun query(text: String): VectorSearchRequest = VectorSearchRequest(
        sessionId = "CLI:u1",
        semanticEmbedding = if (text == "alpha") listOf(1.0f, 0.0f) else listOf(0.0f, 1.0f),
        emotionalEmbedding = BioVector.Neutral.toList(),
        room = null,
        kind = null,
        limit = 1,
        incarnationId = "incarnation-1",
    )

    private fun ids(hits: List<VectorSearchHit>): List<String> = hits.map { it.memoryId }

    private fun entry(
        id: String,
        content: String,
        sessionId: String = "CLI:u1",
        room: MemoryRoom = MemoryRoom.EVENT_ROOM,
        kind: MemoryKind = MemoryKind.RAW,
        incarnationId: String = "incarnation-1",
        visibility: MemoryVisibility = MemoryVisibility.ScopeShared(sessionId),
        semanticEmbedding: List<Float> = if (content == "alpha") listOf(1.0f, 0.0f) else listOf(0.0f, 1.0f),
    ): MemoryEntry = MemoryEntry(
        id = id,
        sessionId = sessionId,
        content = content,
        room = room,
        kind = kind,
        semanticEmbedding = semanticEmbedding,
        emotionalEmbedding = BioVector.Neutral.toList(),
        metadata = MemoryMetadata(
            snapshot8D = BioVector.Neutral,
            omegaState = 0.0f,
            deltaVec = io.openeden.bio.VectorDelta.Zero,
            snapshotOrigin = BioVector.Neutral,
            userId = "u1",
            incarnationId = incarnationId,
            visibility = visibility,
        ),
    )
}
