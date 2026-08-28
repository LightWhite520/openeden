package io.openeden.server.persistence.sqldelight

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryContentFingerprint
import io.openeden.memory.MemoryExclusionContext
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryLineage
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.MemoryVisibility
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalRequest
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.memory.VectorIndex
import io.openeden.memory.VectorSearchHit
import io.openeden.memory.VectorSearchRequest
import io.openeden.server.persistence.sqldelight.SqlDelightMemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import io.openeden.runtime.inference.RecordingInferenceExecutor
import io.openeden.server.db.Database
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.util.Properties
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
                lineage = MemoryLineage(
                    sourceTurnIds = listOf("turn-2", "turn-1", "turn-2"),
                    sourceMemoryIds = listOf("raw-2", "raw-1"),
                ),
                contentFingerprint = "v1:sha-256:test",
                incarnationId = "legacy-incarnation",
                sourceSessionId = "QQ:42",
                canonicalSubjectId = "QQ:u1",
                visibility = MemoryVisibility.ScopeShared("QQ:42"),
                platform = "QQ",
            ),
            createdAtMs = 1_787_384_632_000L,
        )

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry, modelId = "local-v1")
        }

        SqlDelightMemoryRepository.open(dbPath).use { reopened ->
            val restored = assertNotNull(reopened.readById("memory-1"))
            assertEquals(entry, restored.entry)
            assertEquals("local-v1", restored.modelId)
            assertEquals(listOf(entry.metadata.snapshot8D), reopened.stableVectors("legacy-incarnation", 32))
        }
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            assertEquals(MemoryVectorProjectionStore.ProjectionStatus.PENDING, projection.read("memory-1")?.status)
            assertEquals("local-v1", projection.read("memory-1")?.modelId)
        }
    }

    @Test
    fun `legacy row migrated without lineage remains backward compatible`() = runTest {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties()).use { driver ->
            createVersionEightMemoryDatabase(driver)
            driver.execute(
                null,
                """
                INSERT INTO memory_entries(
                    id, session_id, user_id, platform, room, kind, content, tags_json, created_at_ms,
                    snapshot_l, snapshot_p, snapshot_e, snapshot_s, snapshot_tau, snapshot_v, snapshot_m, snapshot_f,
                    omega_state, delta_l, delta_p, delta_e, delta_s, delta_tau, delta_v, delta_m, delta_f,
                    origin_l, origin_p, origin_e, origin_s, origin_tau, origin_v, origin_m, origin_f
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                0,
            ) {
                bindString(0, "legacy-memory")
                bindString(1, "CLI:legacy")
                bindString(2, "user")
                bindString(3, "CLI")
                bindString(4, "EVENT_ROOM")
                bindString(5, "RAW")
                bindString(6, "legacy")
                bindString(7, "[]")
                bindLong(8, 1L)
                repeat(25) { index -> bindDouble(index + 9, 0.0) }
            }
            // Version 8 is the pre-migration state; opening runs 8.sqm and then 9.sqm.
            driver.execute(null, "PRAGMA user_version = 8", 0).value
        }

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            val restored = assertNotNull(repository.readById("legacy-memory"))
            assertEquals(MemoryLineage.Empty, restored.entry.metadata.lineage)
            assertEquals(null, restored.entry.metadata.contentFingerprint)
        }
    }

    @Test
    fun `overflow lineage persists exact ids and explicit overflow metadata`() = runTest {
        val sourceTurnIds = (1..300).map { "turn-$it" }
        val sourceMemoryIds = (1..300).map { "raw-${it.toString().padStart(3, '0')}" }
        val entry = MemoryEntry(
            id = "overflow-memory",
            sessionId = "CLI:overflow",
            content = "overflow",
            room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.NARRATIVE,
            tags = emptySet(),
            metadata = MemoryMetadata(
                snapshot8D = BioVector.Neutral,
                omegaState = 0.0f,
                deltaVec = VectorDelta.Zero,
                snapshotOrigin = BioVector.Neutral,
                userId = "user",
                lineage = MemoryLineage(sourceTurnIds, sourceMemoryIds),
            ),
            semanticEmbedding = emptyList(),
            emotionalEmbedding = emptyList(),
        )

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry)
        }

        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties()).use { driver ->
            driver.executeQuery(
                null,
                "SELECT source_turn_ids_json, source_memory_ids_json FROM memory_entries WHERE id = ?",
                { cursor ->
                    check(cursor.next().value)
                    val turns = checkNotNull(cursor.getString(0))
                    val memories = checkNotNull(cursor.getString(1))
                    val turnObject = Json.parseToJsonElement(turns).jsonObject
                    val memoryObject = Json.parseToJsonElement(memories).jsonObject
                    assertEquals(300, turnObject["overflowCount"]?.jsonPrimitive?.int)
                    assertEquals(300, memoryObject["overflowCount"]?.jsonPrimitive?.int)
                    assertTrue(turnObject["completeSourceDigest"]?.jsonPrimitive?.content?.isNotBlank() == true)
                    assertTrue(memoryObject["completeSourceDigest"]?.jsonPrimitive?.content?.isNotBlank() == true)
                    assertTrue(turnObject["rangeStart"]?.jsonPrimitive?.content == "turn-1")
                    assertTrue(turnObject["rangeEnd"]?.jsonPrimitive?.content == "turn-300")
                    assertTrue(memories.contains("raw-001"))
                    assertFalse(memories.contains("raw-300"))
                    assertTrue(PersistedMemoryLineage.overlaps(turns, listOf("turn-299"), sourceTurns = true))
                    assertFalse(PersistedMemoryLineage.overlaps(memories, listOf("raw-300"), sourceTurns = false))
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                0,
            ) { bindString(0, "overflow-memory") }
        }

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            val restored = assertNotNull(repository.readById("overflow-memory"))
            assertEquals(256, restored.entry.metadata.lineage.sourceTurnIds.size)
            assertEquals(256, restored.entry.metadata.lineage.sourceMemoryIds.size)
            assertEquals(null, restored.entry.metadata.contentFingerprint)
        }

        val nonContiguous = PersistedMemoryLineage.encode(
            MemoryLineage(sourceTurnIds = (1..300).filter { it != 150 }.map { "turn-$it" }),
            Json,
        )
        assertFalse(nonContiguous.sourceTurnIdsJson.contains("rangeStart"))
    }

    @Test
    fun `leading zero turn ids use their actual ids for a verified range`() = runTest {
        val encoded = PersistedMemoryLineage.encode(
            MemoryLineage(sourceTurnIds = (1..300).map { "turn-${it.toString().padStart(3, '0')}" }),
            Json,
        )

        val turns = Json.parseToJsonElement(encoded.sourceTurnIdsJson).jsonObject
        assertEquals("turn-001", turns["rangeStart"]?.jsonPrimitive?.content)
        assertEquals("turn-300", turns["rangeEnd"]?.jsonPrimitive?.content)
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
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            )

            assertEquals(listOf(second.id, first.id), result.memories.map { it.id })
            assertEquals(1, remoteIndex.searchCount)
            assertEquals(0, remoteIndex.rebuildCount)
        }
    }

    @Test
    fun `private subject candidates are excluded before retrieval ranking`() = runTest {
        val privateEntry = memoryEntry("QQ:private:1000:raw", "QQ:private", "private")
            .copy(
                metadata = MemoryMetadata(
                    snapshot8D = BioVector.Neutral,
                    omegaState = 0.0f,
                    deltaVec = VectorDelta.Zero,
                    snapshotOrigin = BioVector.Neutral,
                    userId = "host",
                    incarnationId = "incarnation-1",
                    sourceSessionId = "QQ:private",
                    canonicalSubjectId = "QQ:host",
                    visibility = MemoryVisibility.PrivateSubject("QQ:host"),
                ),
            )
        val sharedEntry = memoryEntry("QQ:group:2000:raw", "QQ:group", "shared")
            .copy(
                metadata = privateEntry.metadata.copy(
                    userId = "guest",
                    sourceSessionId = "QQ:group",
                    canonicalSubjectId = "QQ:guest",
                    visibility = MemoryVisibility.IncarnationShared,
                ),
            )

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(privateEntry, modelId = "local-v1")
            repository.write(sharedEntry, modelId = "local-v1")

            val result = repository.retrieve(
                RetrievalRequest(
                    sessionId = "QQ:group",
                    userId = "guest",
                    canonicalSubjectId = "QQ:guest",
                    incarnationId = "incarnation-1",
                    userInput = "query",
                    currentVector = BioVector.Neutral,
                    origin = BioVector.Neutral,
                    mode = RetrievalMode.CONGRUENT,
                ),
            )

            assertEquals(listOf(sharedEntry.id), result.memories.map { it.id })
        }
    }

    @Test
    fun `same session retrieval normalizes one incarnation through index and hydration`() = runTest {
        val first = memoryEntry("QQ:group:1000:raw", "QQ:group", "first").copy(
            metadata = MemoryMetadata(
                snapshot8D = BioVector.Neutral,
                omegaState = 0.0f,
                deltaVec = VectorDelta.Zero,
                snapshotOrigin = BioVector.Neutral,
                userId = "user",
                incarnationId = "incarnation-1",
                sourceSessionId = "QQ:group",
                canonicalSubjectId = "QQ:user",
                visibility = MemoryVisibility.IncarnationShared,
            ),
        )
        val second = first.copy(
            id = "QQ:group:2000:raw",
            content = "second",
            metadata = first.metadata.copy(incarnationId = "incarnation-2"),
        )
        val remoteIndex = FakeVectorIndex(
            listOf(
                VectorSearchHit(first.id, null, 1.0f, 1.0f),
                VectorSearchHit(second.id, null, 1.0f, 1.0f),
            ),
        )

        SqlDelightMemoryRepository.open(dbPath, index = remoteIndex).use { repository ->
            repository.write(first)
            repository.write(second)

            val result = repository.retrieve(
                RetrievalRequest(
                    sessionId = "QQ:group",
                    userId = "user",
                    canonicalSubjectId = "QQ:user",
                    incarnationId = " incarnation-1 ",
                    userInput = "query",
                    currentVector = BioVector.Neutral,
                    origin = BioVector.Neutral,
                    mode = RetrievalMode.CONGRUENT,
                ),
            )

            assertEquals(listOf(first.id), result.memories.map { it.id })
            assertTrue(remoteIndex.requests.all { it.incarnationId == "incarnation-1" })
        }
    }

    @Test
    fun `local fallback reloads when retrieval switches back to an earlier incarnation`() = runTest {
        val first = memoryEntry("QQ:group:1000:raw", "QQ:group", "first").copy(
            metadata = memoryEntry("QQ:group:1000:raw", "QQ:group", "first").metadata.copy(
                incarnationId = "incarnation-1",
                sourceSessionId = "QQ:group",
                canonicalSubjectId = "QQ:user",
                visibility = MemoryVisibility.IncarnationShared,
            ),
        )
        val second = first.copy(
            id = "QQ:group:2000:raw",
            content = "second",
            metadata = first.metadata.copy(incarnationId = "incarnation-2"),
        )

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(first)
            repository.write(second)

            fun request(incarnationId: String) = RetrievalRequest(
                sessionId = "QQ:group",
                userId = "user",
                canonicalSubjectId = "QQ:user",
                incarnationId = incarnationId,
                userInput = "query",
                currentVector = BioVector.Neutral,
                origin = BioVector.Neutral,
                mode = RetrievalMode.CONGRUENT,
            )

            assertEquals(listOf(first.id), repository.retrieve(request("incarnation-1")).memories.map { it.id })
            assertEquals(listOf(second.id), repository.retrieve(request("incarnation-2")).memories.map { it.id })
            assertEquals(listOf(first.id), repository.retrieve(request("incarnation-1")).memories.map { it.id })
        }
    }

    @Test
    fun `blank retrieval incarnation fails closed for non operator visibility`() = runTest {
        val seed = memoryEntry("QQ:group:1000:raw", "QQ:group", "candidate").copy(
            metadata = MemoryMetadata(
                snapshot8D = BioVector.Neutral,
                omegaState = 0.0f,
                deltaVec = VectorDelta.Zero,
                snapshotOrigin = BioVector.Neutral,
                userId = "user",
                incarnationId = "incarnation-1",
                sourceSessionId = "QQ:group",
                canonicalSubjectId = "QQ:user",
                visibility = MemoryVisibility.PrivateSubject("QQ:user"),
            ),
        )
        val entries = listOf(
            seed,
            seed.copy(id = "QQ:group:2000:raw", metadata = seed.metadata.copy(visibility = MemoryVisibility.ScopeShared("QQ:group"))),
            seed.copy(id = "QQ:group:3000:raw", metadata = seed.metadata.copy(visibility = MemoryVisibility.IncarnationShared)),
        )
        val remoteIndex = FakeVectorIndex(entries.map { VectorSearchHit(it.id, null, 1.0f, 1.0f) })

        SqlDelightMemoryRepository.open(dbPath, index = remoteIndex).use { repository ->
            entries.forEach { repository.write(it) }

            val result = repository.retrieve(
                RetrievalRequest(
                    sessionId = "QQ:group",
                    userId = "user",
                    canonicalSubjectId = "QQ:user",
                    userInput = "query",
                    currentVector = BioVector.Neutral,
                    origin = BioVector.Neutral,
                    mode = RetrievalMode.CONGRUENT,
                ),
            )

            assertTrue(result.memories.isEmpty())
            assertEquals(0, remoteIndex.searchCount)
        }
    }

    @Test
    fun `sql delight uses explicit persisted lineage ranges without digest overlap`() = runTest {
        val sourceTurnIds = (1..300).map { "turn-$it" }
        val entry = memoryEntry("QQ:42:1000:raw", "QQ:42", "range candidate").copy(
            metadata = MemoryMetadata(
                snapshot8D = BioVector.Neutral,
                omegaState = 0.0f,
                deltaVec = VectorDelta.Zero,
                snapshotOrigin = BioVector.Neutral,
                userId = "u1",
                lineage = MemoryLineage(sourceTurnIds = sourceTurnIds),
            ),
        )

        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry)
        }
        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            val result = repository.retrieve(
                RetrievalRequest(
                    "QQ:42",
                    "query",
                    BioVector.Neutral,
                    BioVector.Neutral,
                    RetrievalMode.CONGRUENT,
                    incarnationId = "legacy-incarnation",
                    exclusionContext = io.openeden.memory.MemoryExclusionContext(
                        sourceTurnIds = setOf("turn-300"),
                    ),
                ),
            )

            assertTrue(result.memories.isEmpty())
            assertTrue(result.lineageExcludedCount >= 1)
            assertTrue(result.underfilled)
        }
    }

    @Test
    fun `local populated hits are ranked without sqlite hydration`() = runTest {
        val entry = memoryEntry("QQ:42:1000:raw", "QQ:42", "local")
        SqlDelightMemoryRepository.open(dbPath).use { repository ->
            repository.write(entry, modelId = "local-v1")
            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            )
            assertEquals(listOf(entry.id), result.memories.map { it.id })
        }
    }

    @Test
    fun `retrieval uses the injected inference executor`() = runTest {
        val executor = RecordingInferenceExecutor()
        val entry = memoryEntry("QQ:42:1000:raw", "QQ:42", "isolated")

        SqlDelightMemoryRepository.open(
            dbPath,
            inferenceExecutor = executor,
        ).use { repository ->
            repository.write(entry, modelId = "local-v1")
            repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            )
        }

        assertTrue(executor.calls > 0)
    }

    @Test
    fun `fallback index only rebuilds memories for the active model`() = runTest {
        val active = memoryEntry("QQ:42:1000:raw", "QQ:42", "active")
        val old = memoryEntry("QQ:42:2000:raw", "QQ:42", "old")
        SqlDelightMemoryRepository.open(dbPath, activeModelId = "local-v2").use { repository ->
            repository.write(active, modelId = "local-v2")
            repository.write(old, modelId = "local-v1")

            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            )

            assertEquals(listOf(active.id), result.memories.map { it.id })
        }
    }

    @Test
    fun `overwriting an indexed memory with an old model removes the stale fallback entry`() = runTest {
        val active = memoryEntry("QQ:42:1000:raw", "QQ:42", "active")
        val old = active.copy(content = "old")
        SqlDelightMemoryRepository.open(dbPath, activeModelId = "local-v2").use { repository ->
            repository.write(active, modelId = "local-v2")
            assertEquals(listOf(active.id), repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            ).memories.map { it.id })

            repository.write(old, modelId = "local-v1")

            assertEquals(emptyList(), repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            ).memories)
        }
    }

    @Test
    fun `writes never mutate the injected retrieval index`() = runTest {
        val active = memoryEntry("QQ:42:1000:raw", "QQ:42", "active")
        val old = memoryEntry("QQ:42:2000:raw", "QQ:42", "old")
        val retrievalIndex = ThrowingMutationIndex()
        var wakeCount = 0
        SqlDelightMemoryRepository.open(
            dbPath,
            activeModelId = "local-v2",
            projectionWake = { wakeCount += 1 },
            index = retrievalIndex,
        ).use { repository ->
            repository.write(active, modelId = "local-v2")
            repository.write(old, modelId = "local-v1")
        }
        assertEquals(2, wakeCount)
        assertEquals(0, retrievalIndex.mutationCalls)
    }

    @Test
    fun `independent populated retrieval hits are validated against sqlite`() = runTest {
        val entry = memoryEntry("QQ:42:1000:raw", "QQ:42", "retrieval")
        val retrievalIndex = RebuildableInMemoryVectorIndex()
        retrievalIndex.insert(entry)
        SqlDelightMemoryRepository.open(dbPath, index = retrievalIndex).use { repository ->
            repository.write(entry.copy(content = "old"), modelId = "old-model")
            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            )
            assertEquals(emptyList(), result.memories)
        }
    }

    @Test
    fun `refreshes outdated embeddings in bounded inference batches and requeues projection`() = runTest {
        val entry = memoryEntry("QQ:42:1000:raw", "QQ:42", "refresh me")
        val embeddingModel = object : io.openeden.memory.MemoryEmbeddingModel {
            override suspend fun embed(text: String): List<Float> = listOf(text.length.toFloat())
            override suspend fun embed(vector: BioVector): List<Float> = listOf(vector.p.toFloat() + 10.0f)
        }
        val inference = RecordingInferenceExecutor()

        SqlDelightMemoryRepository.open(
            dbPath,
            embeddingModel = embeddingModel,
            activeModelId = "local-v2",
        ).use { repository ->
            repository.write(entry, modelId = "old-model")
            assertEquals(1, repository.refreshOutdatedEmbeddings(inference, batchSize = 1))
            val refreshed = assertNotNull(repository.readById(entry.id))
            assertEquals("local-v2", refreshed.modelId)
            assertEquals(listOf(entry.content.length.toFloat()), refreshed.entry.semanticEmbedding)
            assertEquals(listOf(10.5f), refreshed.entry.emotionalEmbedding)
        }

        assertEquals(1, inference.calls)
        MemoryVectorProjectionStore.open(dbPath).use { projection ->
            assertEquals(MemoryVectorProjectionStore.ProjectionStatus.PENDING, projection.read(entry.id)?.status)
            assertEquals("local-v2", projection.read(entry.id)?.modelId)
        }
    }

    @Test
    fun `refresh invalidates loaded fallback sessions before the next retrieval`() = runTest {
        val first = memoryEntry("QQ:42:1000:raw", "QQ:42", "first").copy(
            semanticEmbedding = listOf(1.0f, 0.0f),
            emotionalEmbedding = listOf(1.0f, 0.0f),
        )
        val second = memoryEntry("QQ:42:2000:raw", "QQ:42", "second").copy(
            semanticEmbedding = listOf(0.0f, 1.0f),
            emotionalEmbedding = listOf(0.0f, 1.0f),
        )
        val embeddingModel = object : io.openeden.memory.MemoryEmbeddingModel {
            override suspend fun embed(text: String): List<Float> = when (text) {
                "first" -> listOf(0.0f, 1.0f)
                "second" -> listOf(1.0f, 0.0f)
                else -> listOf(1.0f, 0.0f)
            }

            override suspend fun embed(vector: BioVector): List<Float> = listOf(1.0f, 0.0f)
        }
        val fallback = RebuildableInMemoryVectorIndex()

        SqlDelightMemoryRepository.open(
            dbPath,
            embeddingModel = embeddingModel,
            activeModelId = "local-v2",
            fallbackIndex = fallback,
            candidateLimit = 1,
        ).use { repository ->
            repository.write(first, modelId = "local-v2")
            repository.write(second, modelId = "local-v2")

            assertEquals(
                listOf(first.id),
                repository.retrieve(
                    RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
                ).memories.map { it.id },
            )

            JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema).use { driver ->
                val database = Database(driver)
                database.memoryQueries.upsertEmbedding(first.id, "old-model", "[1.0,0.0]", "[1.0,0.0]", "READY")
                database.memoryQueries.upsertEmbedding(second.id, "old-model", "[0.0,1.0]", "[0.0,1.0]", "READY")
            }

            repository.refreshOutdatedEmbeddings(RecordingInferenceExecutor(), batchSize = 2)

            assertEquals(
                listOf(second.id, first.id),
                repository.retrieve(
                    RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
                ).memories.map { it.id },
            )
        }
    }

    @Test
    fun `sql delight retrieval overfetches and backfills remote candidates before final capacity`() = runTest {
        val memories = (1..12).map { index ->
            memoryEntry("QQ:42:${index * 1000}:raw", "QQ:42", "candidate-$index")
        }
        val wrongModel = memoryEntry("QQ:42:99000:raw", "QQ:42", "wrong-model")
        val remoteIndex = FakeVectorIndex(
            listOf(VectorSearchHit(wrongModel.id, null, 1.0f, 1.0f)) + memories.map { entry ->
                VectorSearchHit(entry.id, null, 1.0f, 1.0f)
            },
        )

        SqlDelightMemoryRepository.open(
            dbPath,
            index = remoteIndex,
            candidateLimit = 1,
        ).use { repository ->
            memories.forEach { repository.write(it, modelId = "local-v1") }
            repository.write(wrongModel, modelId = "other-model")

            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.CONGRUENT, incarnationId = "legacy-incarnation"),
            )

            assertTrue(remoteIndex.lastLimit >= 30)
            assertEquals(10, result.memories.size)
            assertEquals(10, result.memories.map { it.id }.toSet().size)
            assertFalse(result.memories.any { it.id == wrongModel.id })
            assertFalse(result.underfilled)
        }
    }

    @Test
    fun `sql delight mixed retrieval searches each emotional lane deeply`() = runTest {
        val memories = (1..12).map { index ->
            memoryEntry("QQ:42:${index * 1000}:raw", "QQ:42", "mixed-candidate-$index")
        }
        val remoteIndex = FakeVectorIndex(memories.map { entry ->
            VectorSearchHit(entry.id, null, 1.0f, 1.0f)
        })

        SqlDelightMemoryRepository.open(
            dbPath,
            index = remoteIndex,
            candidateLimit = 1,
        ).use { repository ->
            memories.forEach { repository.write(it, modelId = "local-v1") }

            val result = repository.retrieve(
                RetrievalRequest("QQ:42", "query", BioVector.Neutral, BioVector.Neutral, RetrievalMode.MIXED, incarnationId = "legacy-incarnation"),
            )

            assertEquals(2, remoteIndex.searchCount)
            assertEquals(listOf(30, 30), remoteIndex.limits)
            assertTrue(remoteIndex.emotionalEmbeddings[0] != remoteIndex.emotionalEmbeddings[1])
            assertEquals(10, result.memories.size)
            assertFalse(result.underfilled)
        }
    }

    @Test
    fun `sql delight preserves emotional-only remote union candidates per lane`() = runTest {
        val semanticOnly = (1..30).map { index ->
            val entry = memoryEntry("QQ:42:${index * 1000}:semantic", "QQ:42", "semantic-$index")
            entry.copy(
                metadata = entry.metadata.copy(
                    lineage = MemoryLineage(sourceTurnIds = listOf("semantic-turn-$index")),
                ),
            )
        }
        val emotionalOnly = (1..30).map { index ->
            memoryEntry("QQ:42:${(index + 100) * 1000}:emotional", "QQ:42", "emotional-$index")
        }
        val remoteIndex = ConcatenatingVectorIndex(semanticOnly, emotionalOnly)

        SqlDelightMemoryRepository.open(
            dbPath,
            index = remoteIndex,
            candidateLimit = 1,
        ).use { repository ->
            (semanticOnly + emotionalOnly).forEach { repository.write(it, modelId = "local-v1") }

            val result = repository.retrieve(
                RetrievalRequest(
                    "QQ:42",
                    "query",
                    BioVector.Neutral,
                    BioVector.Neutral,
                    RetrievalMode.CONGRUENT,
                    incarnationId = "legacy-incarnation",
                    exclusionContext = MemoryExclusionContext(
                        sourceTurnIds = semanticOnly.mapTo(hashSetOf()) {
                            it.metadata.lineage.sourceTurnIds.single()
                        },
                    ),
                ),
            )

            assertEquals(30, remoteIndex.lastLimit)
            assertEquals(10, result.memories.size)
            assertTrue(result.memories.all { it.id.contains(":emotional") })
            assertFalse(result.underfilled)
        }
    }

    private fun createVersionEightMemoryDatabase(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE memory_entries (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                platform TEXT NOT NULL,
                room TEXT NOT NULL,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                snapshot_l REAL NOT NULL,
                snapshot_p REAL NOT NULL,
                snapshot_e REAL NOT NULL,
                snapshot_s REAL NOT NULL,
                snapshot_tau REAL NOT NULL,
                snapshot_v REAL NOT NULL,
                snapshot_m REAL NOT NULL,
                snapshot_f REAL NOT NULL,
                omega_state REAL NOT NULL,
                delta_l REAL NOT NULL,
                delta_p REAL NOT NULL,
                delta_e REAL NOT NULL,
                delta_s REAL NOT NULL,
                delta_tau REAL NOT NULL,
                delta_v REAL NOT NULL,
                delta_m REAL NOT NULL,
                delta_f REAL NOT NULL,
                origin_l REAL NOT NULL,
                origin_p REAL NOT NULL,
                origin_e REAL NOT NULL,
                origin_s REAL NOT NULL,
                origin_tau REAL NOT NULL,
                origin_v REAL NOT NULL,
                origin_m REAL NOT NULL,
                origin_f REAL NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE memory_embeddings (
                memory_id TEXT NOT NULL PRIMARY KEY,
                model_id TEXT NOT NULL,
                semantic_json TEXT NOT NULL,
                emotional_json TEXT NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent(),
            0,
        )
        // Version 8 is immediately before 8.sqm; opening runs 8.sqm and then 9.sqm.
        driver.execute(null, "PRAGMA user_version = 8", 0).value
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
        var lastLimit = 0
        val limits = mutableListOf<Int>()
        val emotionalEmbeddings = mutableListOf<List<Float>?>()
        val requests = mutableListOf<VectorSearchRequest>()

        override suspend fun insert(entry: MemoryEntry) = Unit
        override suspend fun remove(memoryId: String) = Unit
        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) {
            rebuildCount += 1
        }
        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
            requests += request
            searchCount += 1
            lastLimit = request.limit
            limits += request.limit
            emotionalEmbeddings += request.emotionalEmbedding
            return hits.take(request.limit)
        }
        override suspend fun markDirty() = Unit
    }

    private class ConcatenatingVectorIndex(
        private val semanticOnly: List<MemoryEntry>,
        private val emotionalOnly: List<MemoryEntry>,
    ) : VectorIndex {
        var lastLimit = 0

        override suspend fun insert(entry: MemoryEntry) = Unit
        override suspend fun remove(memoryId: String) = Unit
        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) = Unit
        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
            lastLimit = request.limit
            return (semanticOnly + emotionalOnly).map { entry ->
                VectorSearchHit(entry.id, null, 1.0f, 1.0f)
            }
        }
        override suspend fun markDirty() = Unit
    }

    private class ThrowingMutationIndex : VectorIndex {
        var mutationCalls = 0

        override suspend fun insert(entry: MemoryEntry): Unit {
            mutationCalls += 1
            error("retrieval index must not be mutated by writes")
        }

        override suspend fun remove(memoryId: String): Unit {
            mutationCalls += 1
            error("retrieval index must not be mutated by writes")
        }

        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) = Unit
        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> = emptyList()
        override suspend fun markDirty() = Unit
    }


    private suspend inline fun SqlDelightMemoryRepository.use(
        block: suspend (SqlDelightMemoryRepository) -> Unit,
    ) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
