package io.openeden.server.persistence.sqldelight

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.server.persistence.sqldelight.SqlDelightMemoryRepository
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
