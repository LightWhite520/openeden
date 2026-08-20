package io.openeden.server.persistence.sqldelight

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import io.openeden.server.persistence.sqldelight.SqlDelightMemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SqlDelightMemoryRepositoryTest {
    private val tempDir = Files.createTempDirectory("openeden-memory-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        runCatching { Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } } }
        runCatching { Files.deleteIfExists(tempDir) }
    }

    @Test
    fun `memory content metadata and model version survive restart`() = runTest {
        val entry = MemoryEntry(
            id = "memory-1",
            sessionId = "QQ:42",
            content = "durable memory",
            room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW,
            tags = setOf("daily", "stable"),
            semanticEmbedding = listOf(0.1f, 0.2f, 0.3f),
            emotionalEmbedding = listOf(0.4f, 0.5f, 0.6f),
            metadata = MemoryMetadata(
                snapshot8D = BioVector(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f),
                omegaState = 0.33f,
                deltaVec = VectorDelta(p = 0.1f, v = -0.05f),
                snapshotOrigin = BioVector.Neutral,
                userId = "u1",
            ),
        )

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry, modelId = "local-v1")
        }

        SqlDelightMemoryRepository.open(dbPath).use { reopened ->
            val restored = assertNotNull(reopened.readById("memory-1"))
            assertEquals(entry, restored.entry)
            assertEquals("local-v1", restored.modelId)
            assertEquals(listOf(entry.metadata.snapshot8D), reopened.stableVectors("QQ:42", 32))
        }
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            assertEquals(MemoryVectorProjectionStore.ProjectionStatus.PENDING, projection.read("memory-1")?.status)
            assertEquals("local-v1", projection.read("memory-1")?.modelId)
        }
    }

    @Test
    fun `rewriting a memory resets its projection work`() = runTest {
        val entry = MemoryEntry(
            id = "QQ:42:1000:raw", sessionId = "QQ:42", content = "durable", room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW, metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u1"),
            semanticEmbedding = listOf(0.1f), emotionalEmbedding = listOf(0.2f),
        )
        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry, modelId = "local-v1")
        }
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            projection.claimDue(1000L, 1, "local-v1")
            projection.reschedule(entry.id, 1000L, "failure")
        }
        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry, modelId = "local-v1")
        }
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            val work = projection.read(entry.id)!!
            assertEquals(MemoryVectorProjectionStore.ProjectionStatus.PENDING, work.status)
            assertEquals(0, work.attempts)
            assertEquals(1000L, work.availableAtMs)
            assertEquals(null, work.lastError)
        }
    }

    @Test
    fun `failed memory write leaves no projection work`() = runTest {
        val repository = SqlDelightMemoryRepository.open(dbPath, transactionFailureHook = { error("injected transaction failure") })
        val entry = MemoryEntry(
            id = "QQ:42:1000:raw", sessionId = "QQ:42", content = "failed", room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW, metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u1"),
            semanticEmbedding = emptyList(), emotionalEmbedding = emptyList(),
        )
        runCatching { repository.write(entry, modelId = "local-v1") }
        repository.close()
        SqlDelightMemoryRepository.open(dbPath).use { reopened ->
            assertEquals(null, reopened.readById(entry.id))
        }
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            assertEquals(null, projection.read(entry.id))
        }
    }

    @Test
    fun `wake failure does not fail committed write`() = runTest {
        val entry = MemoryEntry(
            id = "QQ:42:1000:raw", sessionId = "QQ:42", content = "wake", room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW, metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u1"),
            semanticEmbedding = emptyList(), emotionalEmbedding = emptyList(),
        )
        SqlDelightMemoryRepository.open(dbPath, projectionWake = { error("wake failure") }).use { repository ->
            repository.write(entry, modelId = "local-v1")
        }
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            assertEquals(MemoryVectorProjectionStore.ProjectionStatus.PENDING, projection.read(entry.id)?.status)
        }
    }

    @Test
    fun `wake cancellation propagates after committed write`() = runTest {
        val entry = MemoryEntry(
            id = "QQ:42:1000:raw", sessionId = "QQ:42", content = "cancel", room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW, metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u1"),
            semanticEmbedding = emptyList(), emotionalEmbedding = emptyList(),
        )
        SqlDelightMemoryRepository.open(dbPath, projectionWake = { throw CancellationException("cancel") }).use { repository ->
            assertFailsWith<CancellationException> { repository.write(entry, modelId = "local-v1") }
        }
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            assertEquals(MemoryVectorProjectionStore.ProjectionStatus.PENDING, projection.read(entry.id)?.status)
        }
    }

    @Test
    fun `raw range is ordered strictly after cursor and excludes narratives`() = runTest {
        fun entry(id: String, kind: MemoryKind) = MemoryEntry(
            id = id, sessionId = "QQ:42", content = id, room = MemoryRoom.EVENT_ROOM, kind = kind,
            metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u1"),
            semanticEmbedding = emptyList(), emotionalEmbedding = emptyList(),
        )
        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry("QQ:42:1000:raw", MemoryKind.RAW))
            repository.write(entry("QQ:42:2000:narrative", MemoryKind.NARRATIVE))
            repository.write(entry("QQ:42:3000:raw", MemoryKind.RAW))
            repository.write(entry("QQ:42:4000:raw", MemoryKind.RAW))
            val rows = repository.rawMemoryRange("QQ:42", "QQ:42:1000:raw", "QQ:42:3000:raw", 10)
            assertEquals(listOf("QQ:42:3000:raw"), rows.map { it.id })
            assertEquals(4000L, repository.latestRawMemory("QQ:42")?.createdAtMs)
        }
    }

    @Test
    fun `remote null-entry hits are hydrated in remote order and filtered by session and model`() = runTest {
        val first = memoryEntry("QQ:42:1000:raw", "QQ:42", "first")
        val second = memoryEntry("QQ:42:2000:raw", "QQ:42", "second")
        val wrongSession = memoryEntry("QQ:99:3000:raw", "QQ:99", "wrong session")
        val wrongModel = memoryEntry("QQ:42:4000:raw", "QQ:42", "wrong model")
        val remoteIndex = FakeVectorIndex(
            listOf(
                VectorSearchHit(second.id, null, 0.9f, 0.9f),
                VectorSearchHit(first.id, null, 0.8f, 0.8f),
                VectorSearchHit(wrongSession.id, null, 0.7f, 0.7f),
                VectorSearchHit(wrongModel.id, null, 0.6f, 0.6f),
            ),
        )
        SqlDelightMemoryRepository.open(dbPath, index = remoteIndex).use { repository ->
            repository.write(first, modelId = "local-v1")
            repository.write(second, modelId = "local-v1")
            repository.write(wrongSession, modelId = "local-v1")
            repository.write(wrongModel, modelId = "other-model")

            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT),
            )

            assertEquals(listOf(second.id, first.id), result.memories.map { it.id })
            assertEquals(1, remoteIndex.searchCount)
            assertEquals(1, remoteIndex.rebuildCount)
        }
    }

    @Test
    fun `local populated hits are ranked without sqlite hydration`() = runTest {
        val entry = memoryEntry("QQ:42:1000:raw", "QQ:42", "local")
        val localIndex = FakeVectorIndex(listOf(VectorSearchHit(entry.id, entry, 0.9f, 0.9f)))
        SqlDelightMemoryRepository.open(dbPath, index = localIndex).use { repository ->
            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT),
            )
            assertEquals(listOf(entry.id), result.memories.map { it.id })
        }
    }

    @Test
    fun `fallback index only rebuilds memories for the active model`() = runTest {
        val active = memoryEntry("QQ:42:1000:raw", "QQ:42", "active")
        val old = memoryEntry("QQ:42:2000:raw", "QQ:42", "old")
        SqlDelightMemoryRepository.open(dbPath, activeModelId = "local-v2").use { repository ->
            repository.write(active, modelId = "local-v2")
            repository.write(old, modelId = "local-v1")

            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT),
            )

            assertEquals(listOf(active.id), result.memories.map { it.id })
        }
    }

    private fun memoryEntry(id: String, sessionId: String, content: String) = MemoryEntry(
        id = id,
        sessionId = sessionId,
        content = content,
        room = MemoryRoom.EVENT_ROOM,
        kind = MemoryKind.RAW,
        metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u1"),
        semanticEmbedding = listOf(1.0f),
        emotionalEmbedding = listOf(1.0f),
    )

    private class FakeVectorIndex(private val hits: List<VectorSearchHit>) : VectorIndex {
        var searchCount = 0
        var rebuildCount = 0

        override suspend fun insert(entry: MemoryEntry) = Unit
        override suspend fun remove(memoryId: String) = Unit
        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) {
            rebuildCount += 1
        }
        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
            searchCount += 1
            return hits.take(request.limit)
        }
        override suspend fun markDirty() = Unit
    }

    private inline fun SqlDelightMemoryRepository.use(
        block: (SqlDelightMemoryRepository) -> Unit,
    ) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
