package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.incarnation.IncarnationState
import io.openeden.runtime.incarnation.IncarnationStateStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightIncarnationStateStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-incarnation-state-test")
    private val dbPath = tempDir.resolve("openeden.db")
    private val activeIncarnationId = "active-incarnation"

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `migration chooses one established state for the active incarnation`() = runTest {
        openVersion11DatabaseWithTwoSessionStates()

        SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            val state = store.read(activeIncarnationId)

            assertEquals(99L, state.evolutionIndex)
            assertEquals(PersonaSubState.AWAKENED, state.personaStartSubState)
            assertEquals(BioVector(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f), state.vector)
        }
    }

    @Test
    fun `version four migration preserves established state with growth awakened selection`() = runTest {
        openVersionFourDatabase()

        SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            val state = store.read("legacy-incarnation")

            assertEquals(99L, state.evolutionIndex)
            assertEquals(PersonaMode.GROWTH, state.personaMode)
            assertEquals(PersonaSubState.AWAKENED, state.personaStartSubState)
            assertEquals(BioVector(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f), state.vector)
            assertEquals(1_500L, state.lastUserActivityMs)
            assertEquals(null, state.lastRuntimeTickAtMs)
        }
    }

    @Test
    fun `version ten migration creates singleton state from established legacy scope`() = runTest {
        openVersionTenDatabaseWithoutSingleton()

        SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            val state = store.read("legacy-incarnation")

            assertEquals(52L, state.evolutionIndex)
            assertEquals(PersonaSubState.TRUE_SELF, state.personaStartSubState)
            assertEquals(BioVector(0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0.2f, 0.3f), state.vector)
            assertEquals(1_200L, state.lastRuntimeTickAtMs)
        }
    }

    @Test
    fun `fresh schema initializes Bio state for transcript active incarnation`() = runTest {
        val transcriptStore = SqlDelightTranscriptStore.open(dbPath)
        val incarnation = try {
            transcriptStore.activeIncarnation()
        } finally {
            transcriptStore.close()
        }

        SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            assertEquals(
                IncarnationStateStore.neutral(
                    incarnation.id,
                    PersonaMode.LEGACY,
                    PersonaSubState.AWAKENED,
                ),
                store.readOrCreate(incarnation.id, PersonaMode.LEGACY, PersonaSubState.AWAKENED),
            )
        }
    }

    @Test
    fun `write preserves full Bio state across restart`() = runTest {
        val transcriptStore = SqlDelightTranscriptStore.open(dbPath)
        val incarnation = try {
            transcriptStore.activeIncarnation()
        } finally {
            transcriptStore.close()
        }
        val expected = IncarnationState(
            incarnationId = incarnation.id,
            vector = BioVector(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f),
            origin = BioVector(0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f),
            omega = OmegaState(0.33f),
            evolutionIndex = 17L,
            personaMode = PersonaMode.GROWTH,
            personaStartSubState = PersonaSubState.TRUE_SELF,
            lastUserActivityMs = 1_700_000_123_456L,
            lastRuntimeTickAtMs = 1_700_000_223_456L,
            shockState = ShockState(
                active = true,
                intensity = 0.71f,
                description = "host went silent",
                triggeredAt = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000L),
                decayLambda = 0.001f,
                shockHeartbeatFired = true,
            ),
        )

        SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            assertEquals(
                IncarnationStateStore.neutral(
                    incarnation.id,
                    PersonaMode.GROWTH,
                    PersonaSubState.TRUE_SELF,
                ),
                store.readOrCreate(incarnation.id, PersonaMode.GROWTH, PersonaSubState.TRUE_SELF),
            )
            store.write(expected)
        }

        SqlDelightIncarnationStateStore.open(dbPath).use { reopened ->
            assertEquals(expected, reopened.read(incarnation.id))
        }
    }

    private fun openVersion11DatabaseWithTwoSessionStates() {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            driver.execute(
                null,
                """
                CREATE TABLE incarnation_state (
                    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                    active_incarnation_id TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    lifecycle_status TEXT NOT NULL DEFAULT 'ACTIVE',
                    lifecycle_changed_at_ms INTEGER NOT NULL DEFAULT 0,
                    termination_reason TEXT,
                    lifecycle_request_id TEXT
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                CREATE TABLE session_state (
                    session_id TEXT NOT NULL PRIMARY KEY,
                    vector_json TEXT NOT NULL,
                    origin_json TEXT NOT NULL,
                    omega REAL NOT NULL,
                    evolution_index INTEGER NOT NULL,
                    persona_mode TEXT,
                    persona_start_sub_state TEXT,
                    last_user_activity_ms INTEGER,
                    last_runtime_tick_at_ms INTEGER,
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
            driver.execute(
                null,
                """
                INSERT INTO incarnation_state(
                    singleton_id, active_incarnation_id, created_at_ms, lifecycle_status, lifecycle_changed_at_ms
                ) VALUES (1, ?, 1, 'ACTIVE', 1)
                """.trimIndent(),
                1,
            ) {
                bindString(0, activeIncarnationId)
            }

            insertLegacyState(
                driver = driver,
                sessionId = "QQ:other-scope",
                vector = BioVector(0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f),
                evolutionIndex = 98L,
                personaStartSubState = "true_self",
                lastUserActivityMs = 2_000L,
            )
            insertLegacyState(
                driver = driver,
                sessionId = "QQ:established-scope",
                vector = BioVector(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f),
                evolutionIndex = 99L,
                personaStartSubState = "awakened",
                lastUserActivityMs = 1_000L,
            )
            driver.execute(null, "PRAGMA user_version = 11", 0)
        }
    }

    private fun openVersionFourDatabase() {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            driver.execute(
                null,
                """
                CREATE TABLE session_state (
                    session_id TEXT NOT NULL PRIMARY KEY,
                    vector_json TEXT NOT NULL,
                    origin_json TEXT NOT NULL,
                    omega REAL NOT NULL,
                    evolution_index INTEGER NOT NULL,
                    persona_mode TEXT,
                    persona_start_sub_state TEXT,
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
            val vector = BioVector(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
            val vectorJson = Json.encodeToString(BioVector.serializer(), vector)
            driver.execute(
                null,
                """
                INSERT INTO session_state(
                    session_id, vector_json, origin_json, omega, evolution_index, persona_mode,
                    persona_start_sub_state, last_user_activity_ms
                ) VALUES (?, ?, ?, ?, ?, 'growth', 'awakened', ?)
                """.trimIndent(),
                6,
            ) {
                bindString(0, "QQ:pre-v5")
                bindString(1, vectorJson)
                bindString(2, vectorJson)
                bindDouble(3, 0.25)
                bindLong(4, 99L)
                bindLong(5, 1_500L)
            }
            driver.execute(null, "PRAGMA user_version = 4", 0)
        }
    }

    private fun openVersionTenDatabaseWithoutSingleton() {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            driver.execute(
                null,
                """
                CREATE TABLE incarnation_state (
                    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                    active_incarnation_id TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    lifecycle_status TEXT NOT NULL DEFAULT 'ACTIVE',
                    lifecycle_changed_at_ms INTEGER NOT NULL DEFAULT 0,
                    termination_reason TEXT,
                    lifecycle_request_id TEXT
                )
                """.trimIndent(),
                0,
            )
            createCurrentSessionStateTable(driver)
            insertLegacyState(
                driver = driver,
                sessionId = "QQ:v10",
                vector = BioVector(0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0.2f, 0.3f),
                evolutionIndex = 52L,
                personaStartSubState = "true_self",
                lastUserActivityMs = 1_100L,
            )
            driver.execute(
                null,
                "UPDATE session_state SET last_runtime_tick_at_ms = 1200 WHERE session_id = 'QQ:v10'",
                0,
            )
            driver.execute(null, "PRAGMA user_version = 10", 0)
        }
    }

    private fun createCurrentSessionStateTable(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE session_state (
                session_id TEXT NOT NULL PRIMARY KEY,
                vector_json TEXT NOT NULL,
                origin_json TEXT NOT NULL,
                omega REAL NOT NULL,
                evolution_index INTEGER NOT NULL,
                persona_mode TEXT,
                persona_start_sub_state TEXT,
                last_user_activity_ms INTEGER,
                last_runtime_tick_at_ms INTEGER,
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
    }

    private fun insertLegacyState(
        driver: JdbcSqliteDriver,
        sessionId: String,
        vector: BioVector,
        evolutionIndex: Long,
        personaStartSubState: String,
        lastUserActivityMs: Long,
    ) {
        val vectorJson = Json.encodeToString(BioVector.serializer(), vector)
        driver.execute(
            null,
            """
            INSERT INTO session_state(
                session_id, vector_json, origin_json, omega, evolution_index, persona_mode,
                persona_start_sub_state, last_user_activity_ms, last_runtime_tick_at_ms
            ) VALUES (?, ?, ?, ?, ?, 'growth', ?, ?, ?)
            """.trimIndent(),
            9,
        ) {
            bindString(0, sessionId)
            bindString(1, vectorJson)
            bindString(2, vectorJson)
            bindDouble(3, 0.4)
            bindLong(4, evolutionIndex)
            bindString(5, personaStartSubState)
            bindLong(6, lastUserActivityMs)
            bindLong(7, lastUserActivityMs + 5L)
        }
    }

    private inline fun SqlDelightIncarnationStateStore.use(block: (SqlDelightIncarnationStateStore) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
