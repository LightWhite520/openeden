package io.openeden.server.persistence.sqldelight

import io.openeden.bio.BioVector
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.affect.ShockState
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.PromptSectionKeys
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.openeden.server.persistence.sqldelight.SqlDelightSessionStateStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class SqlDelightSessionStateStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-db-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(tempDir)
    }

    private val sample = SessionState(
        sessionId = "QQ:42",
        vector = BioVector(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f),
        origin = BioVector.Neutral,
        omega = OmegaState(0.33f),
        shockState = ShockState(
            active = true,
            intensity = 0.71f,
            description = "x",
            triggeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            decayLambda = 0.001f,
            shockHeartbeatFired = true,
        ),
        evolutionIndex = 17,
        lastUserActivityMs = 1_700_000_123_456L,
    )

    @Test
    fun `write then read round-trips full session state`() = runTest {
        val store = SqlDelightSessionStateStore.open(dbPath)
        try {
            store.write(sample)
            assertEquals(sample, store.read("QQ:42"))
            assertTrue("QQ:42" in store.sessionIds())
        } finally {
            store.close()
        }
    }

    @Test
    fun `unknown session reads as neutral, not null`() = runTest {
        val store = SqlDelightSessionStateStore.open(dbPath)
        try {
            val state = store.readOrCreate("QQ:missing")
            assertEquals(0, state.evolutionIndex)
            assertNull(state.shockState)
            assertNull(state.lastUserActivityMs)
        } finally {
            store.close()
        }
    }

    @Test
    fun `state survives a simulated restart (new store on same file)`() = runTest {
        SqlDelightSessionStateStore.open(dbPath).use { store ->
            store.write(sample.copy(evolutionIndex = 99))
        }
        // A fresh store on the same file == process restart. Growth must not reset (§1.1).
        SqlDelightSessionStateStore.open(dbPath).use { reopened ->
            assertEquals(99, reopened.read("QQ:42").evolutionIndex)
        }
    }

    @Test
    fun `persona starting point survives restart and ignores later config changes`() = runTest {
        SqlDelightSessionStateStore.open(dbPath).use { store ->
            DevelopmentMessagePipeline.create(persona(PersonaSubState.TRUE_SELF), store = store)
                .handle(request())
        }

        SqlDelightSessionStateStore.open(dbPath).use { reopened ->
            val result = DevelopmentMessagePipeline.create(
                persona(PersonaSubState.AWAKENED, PersonaMode.LEGACY),
                store = reopened,
            )
                .handle(request())

            assertTrue("TRUE_SELF" in result.promptPreview)
            assertTrue("\"persona_mode\": \"GROWTH\"" in result.promptPreview)
            assertTrue("AWAKENED\"" !in result.promptPreview)
        }
    }

    @Test
    fun `write rejects changing an existing persona selection`() = runTest {
        SqlDelightSessionStateStore.open(dbPath).use { store ->
            store.write(sample)

            assertFailsWith<IllegalArgumentException> {
                store.write(sample.copy(personaStartSubState = PersonaSubState.AWAKENED))
            }
        }
    }

    @Test
    fun `version four sessions migrate without persona downgrade`() = runTest {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        driver.execute(
            null,
            """
            CREATE TABLE session_state (
                session_id TEXT NOT NULL PRIMARY KEY,
                vector_json TEXT NOT NULL,
                origin_json TEXT NOT NULL,
                omega REAL NOT NULL,
                evolution_index INTEGER NOT NULL,
                last_user_activity_ms INTEGER,
                shock_active INTEGER,
                shock_intensity REAL,
                shock_description TEXT,
                shock_triggered_at_ms INTEGER,
                shock_decay_lambda REAL,
                shock_heartbeat_fired INTEGER
            )
            """.trimIndent(),
            0,
        )
        val vectorJson = Json.encodeToString(BioVector.serializer(), BioVector.Neutral)
        driver.execute(
            null,
            "INSERT INTO session_state(session_id, vector_json, origin_json, omega, evolution_index) VALUES (?, ?, ?, ?, ?)",
            5,
        ) {
            bindString(0, "QQ:migrated")
            bindString(1, vectorJson)
            bindString(2, vectorJson)
            bindDouble(3, 0.0)
            bindLong(4, 99L)
        }
        driver.execute(null, "PRAGMA user_version = 4", 0)
        driver.close()

        SqlDelightSessionStateStore.open(dbPath).use { store ->
            val migrated = store.read("QQ:migrated")
            assertEquals(PersonaMode.GROWTH, migrated.personaMode)
            assertEquals(PersonaSubState.AWAKENED, migrated.personaStartSubState)
        }
    }

    private fun persona(
        startSubState: PersonaSubState,
        mode: PersonaMode = PersonaMode.GROWTH,
    ) = PersonaConfig(
        mode = mode,
        startSubState = startSubState,
        promptSections = mapOf(
            PromptSectionKeys.PersonaBase to "base",
            PromptSectionKeys.OutputLayerRules to "rules",
            PromptSectionKeys.PreCommandPatch to "pre",
            PromptSectionKeys.TrueSelfPatch to "true",
            PromptSectionKeys.AwakenedPatch to "awake",
            PromptSectionKeys.Heartbeat to "hb",
            PromptSectionKeys.ShockHeartbeat to "shock",
            PromptSectionKeys.DiaryNarrative to "diary",
        ),
    )

    private fun request() = DevelopmentMessageRequest(
        platform = "QQ",
        scopeId = "42",
        userId = "u1",
        text = "hello",
        emotionConfidence = 0.49f,
    )

    private inline fun SqlDelightSessionStateStore.use(block: (SqlDelightSessionStateStore) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
