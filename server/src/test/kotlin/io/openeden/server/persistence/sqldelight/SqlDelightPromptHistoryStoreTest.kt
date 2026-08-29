package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.server.db.Database
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.PromptHistoryAssembler
import io.openeden.transcript.PromptHistoryCompactor
import io.openeden.transcript.PromptHistorySerializer
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightPromptHistoryStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-prompt-history-test")
    private val dbPath = tempDir.resolve("openeden.db")
    private val assembler = PromptHistoryAssembler(
        serializer = PromptHistorySerializer(tokenEstimator = { it.encodeToByteArray().size }),
        turnCeiling = 2,
        minimumMutableTailTurns = 4,
    )

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `sealed chunks survive restart and four turn tail stays mutable`() = runTest {
        var store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        val incarnationId = store.activeIncarnation().id
        (1..6).forEach { store.append(turn(it, incarnationId, "QQ:alpha", "alpha")) }

        val first = store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
        assertEquals(listOf("alpha-turn-1", "alpha-turn-2"), first.stableChunks.single().turnIds)
        assertEquals((3..6).map { "alpha-turn-$it" }, first.mutableTail.map { it.turnId }.distinct())
        val stable = first.stableChunks.single()
        store.append(turn(7, incarnationId, "QQ:alpha", "alpha"))
        val afterAppend = store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
        assertEquals(stable, afterAppend.stableChunks.single())
        store.close()

        store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        try {
            val restored = store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
            assertEquals(afterAppend, restored)
        } finally {
            store.close()
        }
    }

    @Test
    fun `prompt history never crosses session boundaries`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        try {
            val incarnationId = store.activeIncarnation().id
            (1..6).forEach { store.append(turn(it, incarnationId, "QQ:alpha", "alpha")) }
            (1..6).forEach { store.append(turn(it, incarnationId, "QQ:beta", "beta")) }

            val alpha = store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
            val beta = store.promptHistory("QQ:beta", requiredTailTurns = 4, tokenBudget = 100_000)

            assertEquals((1..6).map { "alpha-turn-$it" }.toSet(), alpha.sourceTurnIds)
            assertEquals((1..6).map { "beta-turn-$it" }.toSet(), beta.sourceTurnIds)
            assertTrue(alpha.sourceTurnIds.intersect(beta.sourceTurnIds).isEmpty())
            assertTrue(alpha.flattenItems().all { it.turnId.startsWith("alpha-turn-") })
            assertTrue(beta.flattenItems().all { it.turnId.startsWith("beta-turn-") })
            assertTrue(alpha.flattenItems().all { "beta" !in it.text })
            assertTrue(beta.flattenItems().all { "alpha" !in it.text })
        } finally {
            store.close()
        }
    }

    @Test
    fun `serializer change advances epoch and preserves old rows`() = runTest {
        var store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        val incarnationId = store.activeIncarnation().id
        (1..6).forEach { store.append(turn(it, incarnationId, "QQ:alpha", "alpha")) }
        val initial = store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
        store.close()

        val changedAssembler = PromptHistoryAssembler(
            serializer = PromptHistorySerializer(
                serializerVersion = assembler.serializer.serializerVersion + 1,
                tokenEstimator = { it.encodeToByteArray().size },
            ),
            turnCeiling = 2,
            minimumMutableTailTurns = 4,
        )
        store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = changedAssembler)
        try {
            val changed = store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
            assertEquals(initial.cacheEpoch + 1, changed.cacheEpoch)
            assertEquals(1, changed.stableChunks.size)
            assertEquals(1, changed.stableChunks.single().cacheEpoch)
            assertEquals(changedAssembler.serializer.serializerVersion, changed.stableChunks.single().serializerVersion)

            val schema = Database.Schema
            assertEquals(schema.version, readSchemaVersion(dbPath))
            assertEquals(1L, countChunks(dbPath, initial.cacheEpoch))
        } finally {
            store.close()
        }
    }

    @Test
    fun `version eighteen legacy serialized chunks migrate and read as wire items`() = runTest {
        val legacyText = createVersionEighteenLegacyPromptHistoryDatabase()
        val legacyAssembler = PromptHistoryAssembler(
            serializer = PromptHistorySerializer(
                serializerVersion = 1,
                tokenEstimator = { it.encodeToByteArray().size },
            ),
            turnCeiling = 2,
            minimumMutableTailTurns = 4,
        )

        val store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = legacyAssembler)
        try {
            val restored = store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
            assertEquals(0L, restored.cacheEpoch)
            assertEquals(
                listOf(
                    Triple("user", "legacy user", "legacy-turn"),
                    Triple("assistant", "legacy assistant", "legacy-turn"),
                ),
                restored.flattenItems().map { Triple(it.role, it.text, it.turnId) },
            )
            assertEquals(legacyText, readLegacySerializedText(dbPath))

            val incarnationId = store.activeIncarnation().id
            (2..7).forEach { store.append(turn(it, incarnationId, "QQ:alpha", "alpha")) }
            store.promptHistory("QQ:alpha", requiredTailTurns = 4, tokenBudget = 100_000)
        } finally {
            store.close()
        }

        assertEquals(Database.Schema.version, readSchemaVersion(dbPath))
        assertEquals(1L, countChunksByStorage(dbPath, "items_json IS NULL AND serialized_text IS NOT NULL"))
        assertEquals(1L, countChunksByStorage(dbPath, "items_json IS NOT NULL AND serialized_text IS NULL"))
        assertEquals(1L, columnCount(dbPath, "prompt_history_state", "summary_text"))
        assertEquals(1L, tableCount(dbPath, "prompt_history_compactions"))
    }

    @Test
    fun `compacted summary and request result survive restart without regeneration`() = runTest {
        var generations = 0
        var store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        val incarnationId = store.activeIncarnation().id
        (1..6).forEach { store.append(turn(it, incarnationId, "QQ:alpha", "alpha")) }

        val compacted = store.compactPromptHistory(
            sessionId = "QQ:alpha",
            requestId = "compact-durable",
            requiredTailTurns = 4,
            tokenBudget = 100_000,
            compactor = PromptHistoryCompactor.validated {
                generations += 1
                validSummaryDocument()
            },
        )
        assertNotNull(compacted.summary)
        assertEquals(1L, compacted.cacheEpoch)
        store.close()

        store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        try {
            val replayed = store.compactPromptHistory(
                sessionId = "QQ:alpha",
                requestId = "compact-durable",
                requiredTailTurns = 4,
                tokenBudget = 100_000,
                compactor = PromptHistoryCompactor.validated {
                    generations += 1
                    error("durable request must not regenerate")
                },
            )

            assertEquals(compacted, replayed)
            assertEquals(compacted, store.promptHistory("QQ:alpha", 4, 100_000))
            assertEquals(1, generations)
            assertEquals("COMPLETED", compactionStatus(dbPath, "compact-durable"))
            assertEquals(compacted.cacheEpoch, compactionResultEpoch(dbPath, "compact-durable"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `invalid compaction result is durably retained without regeneration`() = runTest {
        var generations = 0
        var store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        val incarnationId = store.activeIncarnation().id
        (1..6).forEach { store.append(turn(it, incarnationId, "QQ:alpha", "alpha")) }
        val source = store.promptHistory("QQ:alpha", 4, 100_000)

        val retained = store.compactPromptHistory(
            sessionId = "QQ:alpha",
            requestId = "compact-invalid-durable",
            requiredTailTurns = 4,
            tokenBudget = 100_000,
            compactor = PromptHistoryCompactor.validated {
                generations += 1
                """{"schema_version":1,"commitments":["missing required fields"]}"""
            },
        )
        assertEquals(source, retained)
        store.close()

        store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        try {
            val replayed = store.compactPromptHistory(
                sessionId = "QQ:alpha",
                requestId = "compact-invalid-durable",
                requiredTailTurns = 4,
                tokenBudget = 100_000,
                compactor = PromptHistoryCompactor.validated {
                    generations += 1
                    validSummaryDocument()
                },
            )

            assertEquals(source, replayed)
            assertNull(replayed.summary)
            assertEquals(1, generations)
            assertEquals("COMPLETED", compactionStatus(dbPath, "compact-invalid-durable"))
            assertEquals(source.cacheEpoch, compactionResultEpoch(dbPath, "compact-invalid-durable"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `summary activation and request completion roll back atomically`() = runTest {
        val store = SqlDelightTranscriptStore.open(dbPath, promptHistoryAssembler = assembler)
        try {
            val incarnationId = store.activeIncarnation().id
            (1..6).forEach { store.append(turn(it, incarnationId, "QQ:alpha", "alpha")) }
            val source = store.promptHistory("QQ:alpha", 4, 100_000)
            java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TRIGGER reject_prompt_history_completion
                        BEFORE UPDATE OF status ON prompt_history_compactions
                        WHEN NEW.status = 'COMPLETED'
                        BEGIN
                            SELECT RAISE(ABORT, 'forced request completion failure');
                        END
                        """.trimIndent(),
                    )
                }
            }

            val retained = store.compactPromptHistory(
                sessionId = "QQ:alpha",
                requestId = "compact-atomic-failure",
                requiredTailTurns = 4,
                tokenBudget = 100_000,
                compactor = PromptHistoryCompactor.validated { validSummaryDocument() },
            )

            assertEquals(source, retained)
            assertEquals(source, store.promptHistory("QQ:alpha", 4, 100_000))
            assertNull(compactionStatus(dbPath, "compact-atomic-failure"))
        } finally {
            store.close()
        }
    }

    private fun turn(index: Int, incarnationId: String, sessionId: String, scopeId: String) = ConversationTurn(
        turnId = "$scopeId-turn-$index",
        incarnationId = incarnationId,
        sessionId = sessionId,
        platform = "QQ",
        scopeId = scopeId,
        userId = "user-1",
        userText = "user-$index",
        assistantText = "assistant-$index",
        completedAtMs = index.toLong(),
    )

    private fun readSchemaVersion(path: Path): Long = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    private fun countChunks(path: Path, epoch: Long): Long = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM prompt_history_chunks WHERE session_id = ? AND cache_epoch = ?").use { statement ->
                statement.setString(1, "QQ:alpha")
                statement.setLong(2, epoch)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    private fun createVersionEighteenLegacyPromptHistoryDatabase(): String {
        Files.createDirectories(dbPath.parent)
        val legacyText = """[OPENEDEN_PROMPT_HISTORY v1]
session_id=QQ:alpha
cache_epoch=0
turn_count=1
[TURN]
turn_id=legacy-turn
incarnation_id=incarnation-a
platform=QQ
scope_id=alpha
user_id=user-1
user_text=legacy user
assistant_text=legacy assistant
completed_at_ms=1
[/TURN]
[/OPENEDEN_PROMPT_HISTORY]"""
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            driver.execute(
                null,
                """
                CREATE TABLE incarnation_state (
                    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                    active_incarnation_id TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TABLE conversation_turns (
                    turn_id TEXT NOT NULL PRIMARY KEY,
                    incarnation_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    platform TEXT NOT NULL,
                    scope_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    user_text TEXT NOT NULL,
                    assistant_text TEXT NOT NULL,
                    completed_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TABLE prompt_history_state (
                    session_id TEXT NOT NULL PRIMARY KEY,
                    cache_epoch INTEGER NOT NULL,
                    serializer_version INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TABLE prompt_history_chunks (
                    chunk_id TEXT NOT NULL PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    cache_epoch INTEGER NOT NULL,
                    first_turn_id TEXT NOT NULL,
                    last_turn_id TEXT NOT NULL,
                    turn_ids_json TEXT NOT NULL,
                    serialized_text TEXT NOT NULL,
                    token_count INTEGER NOT NULL,
                    fingerprint TEXT NOT NULL,
                    serializer_version INTEGER NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE INDEX prompt_history_chunks_session_epoch
                ON prompt_history_chunks(session_id, cache_epoch, first_turn_id, last_turn_id, chunk_id)
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                "INSERT INTO incarnation_state VALUES (1, 'incarnation-a', 0)",
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO conversation_turns VALUES (
                    'legacy-turn', 'incarnation-a', 'QQ:alpha', 'QQ', 'alpha', 'user-1',
                    'legacy user', 'legacy assistant', 1
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                "INSERT INTO prompt_history_state VALUES ('QQ:alpha', 0, 1, 1)",
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO prompt_history_chunks(
                    chunk_id, session_id, cache_epoch, first_turn_id, last_turn_id,
                    turn_ids_json, serialized_text, token_count, fingerprint, serializer_version
                ) VALUES (?, 'QQ:alpha', 0, 'legacy-turn', 'legacy-turn', ?, ?, ?, ?, 1)
                """.trimIndent(),
                5,
            ) {
                bindString(0, "QQ:alpha|0|legacy-turn")
                bindString(1, "[\"legacy-turn\"]")
                bindString(2, legacyText)
                bindLong(3, legacyText.encodeToByteArray().size.toLong())
                bindString(4, PromptHistorySerializer(serializerVersion = 1).fingerprint(legacyText))
            }
            driver.execute(null, "PRAGMA user_version = 18", 0)
        }
        return legacyText
    }

    private fun readLegacySerializedText(path: Path): String? = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT serialized_text FROM prompt_history_chunks WHERE chunk_id = 'QQ:alpha|0|legacy-turn'")
                    .use { result ->
                        result.next()
                        result.getString(1)
                    }
            }
        }

    private fun countChunksByStorage(path: Path, predicate: String): Long = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM prompt_history_chunks WHERE $predicate").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    private fun columnCount(path: Path, table: String, column: String): Long = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = '$column'")
                    .use { result ->
                        result.next()
                        result.getLong(1)
                    }
            }
        }

    private fun tableCount(path: Path, table: String): Long = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")
                .use { statement ->
                    statement.setString(1, table)
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }
        }

    private fun compactionStatus(path: Path, requestId: String): String? = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.prepareStatement("SELECT status FROM prompt_history_compactions WHERE request_id = ?")
                .use { statement ->
                    statement.setString(1, requestId)
                    statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
                }
        }

    private fun compactionResultEpoch(path: Path, requestId: String): Long? = java.sql.DriverManager
        .getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .use { connection ->
            connection.prepareStatement("SELECT result_cache_epoch FROM prompt_history_compactions WHERE request_id = ?")
                .use { statement ->
                    statement.setString(1, requestId)
                    statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                }
        }

    private fun validSummaryDocument(): String =
        """
        {
          "schema_version": 1,
          "named_entities": ["小林"],
          "commitments": ["约定周五继续完成模型评审"],
          "unresolved_questions": ["是否需要补充回滚演练"],
          "relationship_facts": ["小林负责最终验收"],
          "chronology": ["先确认范围", "随后完成实现"]
        }
        """.trimIndent()
}
