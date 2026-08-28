package io.openeden.server.persistence.sqldelight

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.relationship.RelationshipEvent
import io.openeden.relationship.RelationshipEventType
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.lifecycle.TerminationReason
import io.openeden.transcript.ConversationTurn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlDelightIncarnationLifecycleRepositoryTest {
    private val tempDir = Files.createTempDirectory("openeden-termination-test")
    private val dbPath = tempDir.resolve("openeden.db")
    private var transcript: SqlDelightTranscriptStore? = null
    private var memory: SqlDelightMemoryRepository? = null
    private var repository: SqlDelightIncarnationLifecycleRepository? = null

    @AfterTest
    fun cleanup() {
        runBlocking { repository?.close() }
        runBlocking {
            memory?.close()
            transcript?.close()
        }
        Files.deleteIfExists(dbPath)
        Files.deleteIfExists(dbPath.resolveSibling("openeden.db.init.lock"))
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `termination leaves only immutable diary archive`() = runTest {
        val incarnationId = seedIncarnation()
        val relationshipStore = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            relationshipStore.append(
                RelationshipEvent(
                    eventId = "relationship-event",
                    incarnationId = incarnationId,
                    canonicalSubjectId = "QQ:u1",
                    sourceTurnId = "turn-1",
                    type = RelationshipEventType.USER_CONFESSION,
                    confidence = 1.0f,
                    evidenceDigest = "confession",
                    createdAtMs = 1_000L,
                ),
            )
        } finally {
            relationshipStore.close()
        }
        val repo = openRepository()

        assertEquals(IncarnationLifecycle.CRITICAL, repo.markCritical())
        assertEquals(IncarnationLifecycle.TERMINATING, repo.beginTermination())
        repo.archiveAndPurge(TerminationReason("critical", 900L))

        assertEquals(IncarnationLifecycle.TERMINATED, repo.read())
        assertEquals(listOf("diary text"), repo.page(incarnationId, 50, null).entries.map { it.content })
        assertEquals(0, count("conversation_turns"))
        assertEquals(0, count("memory_entries"))
        assertEquals(0, count("relationship_state"))
        assertEquals(0, count("relationship_events"))

        val freshId = repo.createFresh("fresh-request", 1_000L)
        assertEquals(IncarnationLifecycle.ACTIVE, repo.read())
        assertEquals(freshId, repo.createFresh("fresh-request", 2_000L))
        assertEquals(listOf("diary text"), repo.page(incarnationId, 50, null).entries.map { it.content })
        assertEquals(0, count("memory_entries"))
    }

    @Test
    fun `termination deletes post commit children before transcript parents`() = runTest {
        seedIncarnation()
        insertPostCommitPlan("turn-1")
        installPostCommitDeleteOrderGuard()
        val repo = openRepository()

        repo.markCritical()
        repo.beginTermination()
        repo.archiveAndPurge(TerminationReason("critical", 900L))

        assertEquals(0, count("turn_post_commit"))
        assertEquals(0, count("conversation_turns"))
    }

    @Test
    fun `termination deletes prompt history chunks before prompt history state`() = runTest {
        seedIncarnation()
        insertPromptHistory()
        installPromptHistoryDeleteOrderGuard()
        val repo = openRepository()

        assertEquals(1, count("prompt_history_chunks"))
        assertEquals(1, count("prompt_history_state"))
        repo.markCritical()
        repo.beginTermination()
        repo.archiveAndPurge(TerminationReason("critical", 900L))

        assertEquals(0, count("prompt_history_chunks"))
        assertEquals(0, count("prompt_history_state"))
    }

    @Test
    fun `archive verification failure rolls back every delete`() = runTest {
        val incarnationId = seedIncarnation()
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                "INSERT INTO diary_archive(archive_entry_id, incarnation_id, source_diary_id, content, original_created_at_ms, archived_at_ms, archive_reason, content_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "corrupt")
                statement.setString(2, incarnationId)
                statement.setString(3, "QQ:42:2000:narrative")
                statement.setString(4, "diary text")
                statement.setLong(5, 2000L)
                statement.setLong(6, 800L)
                statement.setString(7, "old")
                statement.setString(8, "wrong")
                statement.executeUpdate()
            }
        }

        val repo = openRepository()
        repo.markCritical()
        repo.beginTermination()

        assertFailsWith<DiaryArchiveVerificationException> {
            repo.archiveAndPurge(TerminationReason("critical", 900L))
        }
        assertEquals(IncarnationLifecycle.TERMINATING, repo.read())
        assertEquals(1, count("conversation_turns"))
        assertEquals(2, count("memory_entries"))
    }

    @Test
    fun `close releases the sqlite connection used by the repository dispatcher`() = runTest {
        seedIncarnation()
        val repo = openRepository()
        repo.read()

        repo.close()
        repository = null
        Files.deleteIfExists(dbPath)
    }

    @Test
    fun `transcript close releases its sqlite connection`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath)
        store.activeIncarnation()
        store.close()

        Files.deleteIfExists(dbPath)
    }

    @Test
    fun `memory close releases its sqlite connection`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath)
        store.close()
        val memoryStore = SqlDelightMemoryRepository.open(dbPath)
        memoryStore.write(memoryEntry("QQ:42:1000:raw", MemoryKind.RAW, "raw text"))
        memoryStore.close()

        Files.deleteIfExists(dbPath)
    }

    private suspend fun seedIncarnation(): String {
        val transcriptStore = SqlDelightTranscriptStore.open(dbPath)
        transcript = transcriptStore
        val active = transcriptStore.activeIncarnation()
        transcriptStore.append(
            ConversationTurn(
                turnId = "turn-1",
                incarnationId = active.id,
                sessionId = "QQ:42",
                platform = "QQ",
                scopeId = "42",
                userId = "u1",
                userText = "hello",
                assistantText = "hi",
                completedAtMs = 1000L,
            ),
        )
        transcriptStore.close()
        transcript = null

        val memoryStore = SqlDelightMemoryRepository.open(dbPath)
        memory = memoryStore
        memoryStore.write(memoryEntry("QQ:42:1000:raw", MemoryKind.RAW, "raw text"))
        memoryStore.write(memoryEntry("QQ:42:2000:narrative", MemoryKind.NARRATIVE, "diary text"))
        memoryStore.close()
        memory = null
        return active.id
    }

    private fun openRepository(): SqlDelightIncarnationLifecycleRepository =
        SqlDelightIncarnationLifecycleRepository.open(dbPath).also { repository = it }

    private fun insertPostCommitPlan(turnId: String) {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                "INSERT INTO turn_post_commit(turn_id, plan_json, completed_stages_json) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, turnId)
                statement.setString(2, "{\"turnId\":\"$turnId\"}")
                statement.setString(3, "[]")
                statement.executeUpdate()
            }
        }
    }

    private fun installPostCommitDeleteOrderGuard() {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TRIGGER enforce_post_commit_delete_order
                    BEFORE DELETE ON conversation_turns
                    WHEN EXISTS (SELECT 1 FROM turn_post_commit WHERE turn_id = OLD.turn_id)
                    BEGIN
                        SELECT RAISE(ABORT, 'post-commit child must be deleted first');
                    END
                    """.trimIndent(),
                )
            }
        }
    }

    private fun insertPromptHistory() {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                "INSERT INTO prompt_history_state(session_id, cache_epoch, serializer_version, updated_at_ms) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "QQ:42")
                statement.setLong(2, 1L)
                statement.setLong(3, 1L)
                statement.setLong(4, 1_000L)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO prompt_history_chunks(
                    chunk_id, session_id, cache_epoch, first_turn_id, last_turn_id,
                    turn_ids_json, serialized_text, token_count, fingerprint, serializer_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "QQ:42|1|turn-1")
                statement.setString(2, "QQ:42")
                statement.setLong(3, 1L)
                statement.setString(4, "turn-1")
                statement.setString(5, "turn-1")
                statement.setString(6, "[\"turn-1\"]")
                statement.setString(7, "serialized history")
                statement.setLong(8, 10L)
                statement.setString(9, "fingerprint")
                statement.setLong(10, 1L)
                statement.executeUpdate()
            }
        }
    }

    private fun installPromptHistoryDeleteOrderGuard() {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TRIGGER enforce_prompt_history_delete_order
                    BEFORE DELETE ON prompt_history_state
                    WHEN EXISTS (
                        SELECT 1 FROM prompt_history_chunks WHERE session_id = OLD.session_id
                    )
                    BEGIN
                        SELECT RAISE(ABORT, 'prompt history chunks must be deleted first');
                    END
                    """.trimIndent(),
                )
            }
        }
    }

    private fun count(table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun memoryEntry(id: String, kind: MemoryKind, content: String) = MemoryEntry(
        id = id,
        sessionId = "QQ:42",
        content = content,
        room = MemoryRoom.EVENT_ROOM,
        kind = kind,
        semanticEmbedding = emptyList(),
        emotionalEmbedding = BioVector.Neutral.toList(),
        metadata = MemoryMetadata(
            snapshot8D = BioVector.Neutral,
            omegaState = 0.4f,
            deltaVec = VectorDelta.Zero,
            snapshotOrigin = BioVector.Neutral,
            userId = "u1",
        ),
    )
}
