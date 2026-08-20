package io.openeden.server.vector

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.server.persistence.sqldelight.MemoryVectorProjectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QdrantProjectionSynchronizerTest {
    @Test
    fun `successful batch acknowledges exactly projected rows`() = runTest {
        val store = FakeStore(work("a"), work("b"))
        val projected = mutableListOf<String>()
        val synchronizer = QdrantProjectionSynchronizer(
            store = store,
            loadEntry = { id -> entry(id) },
            project = { entries -> projected += entries.map { it.id } },
            modelId = "model",
            nowMs = { 10L },
        )

        assertEquals(2, synchronizer.drainOnce())
        assertEquals(listOf("a", "b"), projected)
        assertEquals(listOf("a", "b"), store.ready)
    }

    @Test
    fun `failed batch is rescheduled and cancellation is propagated`() = runTest {
        val store = FakeStore(work("a"))
        val synchronizer = QdrantProjectionSynchronizer(
            store = store,
            loadEntry = { entry(it) },
            project = { error("remote down") },
            modelId = "model",
            nowMs = { 10L },
        )
        assertEquals(1, synchronizer.drainOnce())
        assertEquals(listOf("a"), store.rescheduled)

        val cancelled = QdrantProjectionSynchronizer(
            store = FakeStore(work("b")),
            loadEntry = { entry(it) },
            project = { throw CancellationException("shutdown") },
            modelId = "model",
            nowMs = { 10L },
        )
        assertFailsWith<CancellationException> { cancelled.drainOnce() }
    }

    @Test
    fun `missing source rows are retried while loaded rows are acknowledged`() = runTest {
        val store = FakeStore(work("present"), work("missing"))
        val projected = mutableListOf<String>()
        val synchronizer = QdrantProjectionSynchronizer(
            store = store,
            loadEntry = { id -> id.takeIf { it == "present" }?.let(::entry) },
            project = { rows -> projected += rows.map { it.id } },
            modelId = "model",
            nowMs = { 10L },
            retryJitterMs = { 0L },
        )

        assertEquals(2, synchronizer.drainOnce())
        assertEquals(listOf("present"), projected)
        assertEquals(listOf("present"), store.ready)
        assertEquals(listOf("missing"), store.rescheduled)
    }

    @Test
    fun `wake starts a single child and drains work`() = runTest {
        val store = FakeStore(work("a"))
        val synchronizer = QdrantProjectionSynchronizer(store, { entry(it) }, { }, "model", nowMs = { 0L }, intervalMs = 60_000L)
        val job = synchronizer.start(this)
        synchronizer.signal()
        testScheduler.runCurrent()
        assertEquals(listOf("a"), store.ready)
        job.cancel()
    }

    @Test
    fun `missing collection requeues ready rows before retrying claimed work`() = runTest {
        val store = FakeStore(work("a"))
        val synchronizer = QdrantProjectionSynchronizer(
            store = store,
            loadEntry = { entry(it) },
            project = { throw io.openeden.server.vector.qdrant.QdrantClientException(io.openeden.server.vector.qdrant.QdrantErrorCategory.HTTP, 404, "missing") },
            modelId = "model",
            nowMs = { 10L },
            retryJitterMs = { 0L },
        )

        synchronizer.drainOnce()

        assertEquals(listOf("model"), store.resetModels)
        assertEquals(listOf("a"), store.rescheduled)
    }

    @Test
    fun `collection loss drains all ready requeue batches`() = runTest {
        val store = FakeStore(work("a"))
        store.resetBatches = mutableListOf(listOf("ready-1"), listOf("ready-2"), emptyList())
        val synchronizer = QdrantProjectionSynchronizer(
            store = store,
            loadEntry = { entry(it) },
            project = { throw io.openeden.server.vector.qdrant.QdrantClientException(io.openeden.server.vector.qdrant.QdrantErrorCategory.HTTP, 404, "missing") },
            modelId = "model",
            nowMs = { 10L },
            retryJitterMs = { 0L },
        )

        synchronizer.drainOnce()

        assertEquals(3, store.resetCalls)
    }

    @Test
    fun `loaded id mismatch is retried and never acknowledged`() = runTest {
        val store = FakeStore(work("expected"))
        val synchronizer = QdrantProjectionSynchronizer(
            store = store,
            loadEntry = { entry("different") },
            project = { _: List<MemoryEntry> -> error("must not project") },
            modelId = "model",
            nowMs = { 10L },
            retryJitterMs = { 0L },
        )

        synchronizer.drainOnce()

        assertEquals(emptyList<String>(), store.ready)
        assertEquals(listOf("expected"), store.rescheduled)
        assertEquals(listOf<String?>("MemoryIdentityMismatch"), store.errors)
    }

    @Test
    fun `retry persists only exception category rather than upstream message`() = runTest {
        val store = FakeStore(work("a"))
        val synchronizer = QdrantProjectionSynchronizer(
            store = store,
            loadEntry = { entry(it) },
            project = { _: List<MemoryEntry> -> error("secret token and memory body") },
            modelId = "model",
            nowMs = { 10L },
            retryJitterMs = { 0L },
        )

        synchronizer.drainOnce()

        assertEquals(listOf<String?>("IllegalStateException"), store.errors)
    }

    private fun work(id: String) = MemoryVectorProjectionStore.ProjectionWork(
        id, "model", MemoryVectorProjectionStore.ProjectionStatus.PENDING, 0, 0L, null, 0L,
    )

    private fun entry(id: String) = MemoryEntry(
        id = id, sessionId = "session", content = id, room = MemoryRoom.EVENT_ROOM, kind = MemoryKind.RAW,
        semanticEmbedding = listOf(1f), emotionalEmbedding = listOf(1f),
        metadata = MemoryMetadata(BioVector.Neutral, 0f, VectorDelta.Zero, BioVector.Neutral, "user"),
    )

    private class FakeStore(vararg initial: MemoryVectorProjectionStore.ProjectionWork) : ProjectionWorkStore {
        private val pending = initial.toMutableList()
        val ready = mutableListOf<String>()
        val rescheduled = mutableListOf<String>()
        val resetModels = mutableListOf<String>()
        var resetBatches = mutableListOf<List<String>>()
        var resetCalls = 0
        val errors = mutableListOf<String?>()
        override suspend fun recoverRunning(nowMs: Long) = Unit
        override suspend fun claimDue(nowMs: Long, batchSize: Int, activeModelId: String) = pending.toList().also { pending.clear() }
        override suspend fun markReady(memoryIds: Collection<String>, nowMs: Long) { ready += memoryIds }
        override suspend fun reschedule(memoryId: String, nowMs: Long, error: String?) { rescheduled += memoryId; errors += error }
        override suspend fun rescheduleWithJitter(memoryId: String, nowMs: Long, error: String?, jitterMs: Long, baseDelayMs: Long) {
            rescheduled += memoryId
            errors += error
        }
        override suspend fun resetReady(modelId: String, nowMs: Long, batchSize: Int): List<String> {
            resetModels += modelId
            resetCalls += 1
            return if (resetBatches.isNotEmpty()) resetBatches.removeAt(0) else emptyList()
        }
    }
}
