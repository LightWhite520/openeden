package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.VectorWriteService
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.TurnCommitOutcome
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightAtomicTurnCommitTest {
    private val tempDir = Files.createTempDirectory("openeden-atomic-turn-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        Files.list(tempDir).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `atomic store reports inserted then already committed without rewriting state`() = runTest {
        val transcriptStore = SqlDelightTranscriptStore.open(dbPath)
        val stateStore = SqlDelightSessionStateStore.open(
            dbPath,
            committedTranscriptStore = transcriptStore,
        )
        try {
            val initial = SessionStateStore.neutral("CLI:local")
            val firstState = initial.copy(evolutionIndex = 1L)
            val turn = ConversationTurn(
                turnId = "stable-turn",
                incarnationId = transcriptStore.activeIncarnation().id,
                sessionId = initial.sessionId,
                platform = "CLI",
                scopeId = "local",
                userId = "user-1",
                userText = "hello",
                assistantText = "response",
                completedAtMs = 100L,
            )

            val inserted = stateStore.writeCommittedTurn(firstState, turn)
            val duplicate = stateStore.writeCommittedTurn(
                firstState.copy(evolutionIndex = 2L),
                turn.copy(completedAtMs = 200L),
            )

            assertEquals(TurnCommitOutcome.INSERTED, inserted)
            assertEquals(TurnCommitOutcome.ALREADY_COMMITTED, duplicate)
            assertEquals(1L, stateStore.read(initial.sessionId).evolutionIndex)
            assertEquals(100L, transcriptStore.page(50).turns.single().completedAtMs)
        } finally {
            stateStore.close()
            transcriptStore.close()
        }
    }

    @Test
    fun `failed transcript insert rolls back the full session state`() = runTest {
        val stateStore = SqlDelightSessionStateStore.open(dbPath)
        val transcriptStore = SqlDelightTranscriptStore.open(dbPath)
        try {
            val initial = SessionState(
                sessionId = "CLI:local",
                vector = BioVector(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f),
                origin = BioVector.Neutral,
                omega = OmegaState(0.2f),
                shockState = ShockState(
                    active = true,
                    intensity = 0.3f,
                    description = "existing shock",
                    triggeredAt = Instant.fromEpochMilliseconds(100L),
                    decayLambda = 0.01f,
                ),
                evolutionIndex = 7L,
                lastUserActivityMs = 200L,
            )
            stateStore.write(initial)
            installRejectTrigger()

            val writer = VectorWriteService(stateStore)
            val rejectedTurn = ConversationTurn(
                turnId = "reject-me",
                incarnationId = transcriptStore.activeIncarnation().id,
                sessionId = initial.sessionId,
                platform = "CLI",
                scopeId = "local",
                userId = "user-1",
                userText = "hello",
                assistantText = "response",
                completedAtMs = 500L,
            )

            assertFailsWith<Throwable> {
                writer.commitTurnLocked(
                    sessionId = initial.sessionId,
                    preTickedSnapshot = BioVector.Neutral,
                    delta = VectorDelta(l = 0.25f, s = 0.2f),
                    shock = ShockState(
                        active = true,
                        intensity = 0.9f,
                        description = "new shock",
                        triggeredAt = Instant.fromEpochMilliseconds(400L),
                        decayLambda = 0.001f,
                    ),
                    lastUserActivityMs = 500L,
                    turn = rejectedTurn,
                )
            }

            assertEquals(initial, stateStore.read(initial.sessionId))
            assertTrue(transcriptStore.page(50).turns.isEmpty())
        } finally {
            transcriptStore.close()
            stateStore.close()
        }
    }

    private fun installRejectTrigger() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        try {
            driver.execute(
                null,
                """
                CREATE TRIGGER reject_test_turn BEFORE INSERT ON conversation_turns
                WHEN NEW.turn_id = 'reject-me'
                BEGIN
                    SELECT RAISE(ABORT, 'test failure');
                END
                """.trimIndent(),
                0,
            )
        } finally {
            driver.close()
        }
    }
}
