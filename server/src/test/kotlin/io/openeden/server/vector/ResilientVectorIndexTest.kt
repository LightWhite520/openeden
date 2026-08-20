package io.openeden.server.vector

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResilientVectorIndexTest {
    @Test
    fun `search falls back after remote failure and inserts keep fallback current`() = runTest {
        val primary = FakeIndex(failSearch = true)
        val fallback = RebuildableInMemoryVectorIndex()
        val resilient = ResilientVectorIndex(primary, fallback, QdrantCircuitBreaker(failureThreshold = 1))
        val memory = entry("m1")

        resilient.insert(memory)
        val hits = resilient.search(VectorSearchRequest("session", listOf(1f), limit = 1))

        assertEquals(listOf("m1"), hits.map { it.memoryId })
        assertTrue(resilient.status().fallbackActive)
        assertEquals(1, primary.insertCount)
    }

    @Test
    fun `cancellation from primary propagates and does not switch circuit`() = runTest {
        val primary = FakeIndex(cancelSearch = true)
        val resilient = ResilientVectorIndex(primary, RebuildableInMemoryVectorIndex(), QdrantCircuitBreaker(failureThreshold = 1))
        assertFailsWith<CancellationException> { resilient.search(VectorSearchRequest("session", listOf(1f))) }
        assertEquals(QdrantCircuitBreaker.State.CLOSED, resilient.status().circuit.state)
    }

    @Test
    fun `rebuild reuses an already materialized collection for both indexes`() = runTest {
        val entries = listOf(entry("m1"), entry("m2"))
        val primary = FakeIndex()
        val resilient = ResilientVectorIndex(primary, RebuildableInMemoryVectorIndex(), QdrantCircuitBreaker())

        resilient.rebuild(entries)

        assertTrue(primary.rebuildInput === entries)
    }

    @Test
    fun `first successful remote operation after fallback reports recovery`() = runTest {
        var now = 0L
        val primary = FakeIndex(failSearch = true)
        val breaker = QdrantCircuitBreaker(failureThreshold = 1, probeIntervalMs = 10, nowMs = { now })
        val resilient = ResilientVectorIndex(primary, RebuildableInMemoryVectorIndex(), breaker)
        resilient.search(VectorSearchRequest("session", listOf(1f)))
        now = 10L
        primary.failSearch = false

        resilient.search(VectorSearchRequest("session", listOf(1f)))

        assertEquals(ResilientVectorIndex.TRACE_RECOVERED, resilient.status().lastTraceTag)
    }

    @Test
    fun `one shot rebuild is consumed by fallback then replayed to primary`() = runTest {
        val primary = FakeIndex()
        val resilient = ResilientVectorIndex(primary, RebuildableInMemoryVectorIndex(), QdrantCircuitBreaker())
        val oneShot = sequenceOf(entry("m1"), entry("m2")).asIterable()

        resilient.rebuild(oneShot)

        assertEquals(listOf("m1", "m2"), primary.rebuildInput?.map { it.id })
    }

    private fun entry(id: String) = MemoryEntry(
        id = id, sessionId = "session", content = id, room = MemoryRoom.EVENT_ROOM, kind = MemoryKind.RAW,
        semanticEmbedding = listOf(1f), emotionalEmbedding = listOf(1f),
        metadata = MemoryMetadata(BioVector.Neutral, 0f, VectorDelta.Zero, BioVector.Neutral, "user"),
    )

    private class FakeIndex(
        var failSearch: Boolean = false,
        private val cancelSearch: Boolean = false,
    ) : VectorIndex {
        var insertCount = 0
        var rebuildInput: Iterable<MemoryEntry>? = null
        override suspend fun insert(entry: MemoryEntry) { insertCount++ }
        override suspend fun remove(memoryId: String) = Unit
        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) { rebuildInput = entries }
        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
            if (cancelSearch) throw CancellationException("cancel")
            if (failSearch) error("remote down")
            return emptyList()
        }
        override suspend fun markDirty() = Unit
    }
}
