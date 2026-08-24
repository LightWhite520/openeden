package io.openeden.server.persistence.sqldelight

import io.openeden.server.db.Database
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.PromptHistoryAssembler
import io.openeden.transcript.PromptHistorySerializer
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals((3..6).map { "alpha-turn-$it" }, first.mutableTail.map { it.turnId })
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
            assertEquals(setOf("QQ:alpha"), alpha.mutableTail.map { it.sessionId }.toSet())
            assertEquals(setOf("alpha"), alpha.mutableTail.map { it.scopeId }.toSet())
            assertEquals(setOf("beta"), beta.mutableTail.map { it.scopeId }.toSet())
            assertEquals(setOf("alpha"), alpha.stableChunks.flatMap { it.turnIds }.map { "alpha" }.toSet())
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
            assertEquals(2, changed.stableChunks.single().serializerVersion)

            val schema = Database.Schema
            assertEquals(schema.version, readSchemaVersion(dbPath))
            assertEquals(1L, countChunks(dbPath, initial.cacheEpoch))
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
}
