package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.incarnation.IncarnationState
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.state.RuntimeConfig
import io.openeden.runtime.state.BackgroundDynamicsReducer
import io.openeden.runtime.state.VectorWriteService
import io.openeden.runtime.tick.RuntimeTickScheduler
import io.openeden.runtime.tick.SineWaveDimension
import io.openeden.runtime.tick.SineWaveFluctuationEngine
import io.openeden.runtime.tick.SineWaveFluctuationProfile
import io.openeden.server.bootstrap.IncarnationBackgroundDynamicsReducerFactory
import io.openeden.relationship.RelationshipEvaluation
import io.openeden.transcript.ConversationTurn
import io.openeden.transcript.TurnCommitOutcome
import io.openeden.transcript.TurnPostCommitPlan
import io.openeden.transcript.TurnPostCommitStage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
    fun `version nineteen established state backfills consumed dynamics from runtime tick`() = runTest {
        openVersionNineteenEstablishedState(lastRuntimeTickAtMs = 4_321L)

        SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            val state = store.read(activeIncarnationId)

            assertEquals(4_321L, state.lastRuntimeTickAtMs)
            assertEquals(4_321L, state.lastVectorDynamicsAtMs)
            assertEquals(0L, state.centroidRevision)
            assertEquals(0L, state.originRevision)
        }
    }

    @Test
    fun `restart between turn and tick preserves complete dynamics composition`() = runTest {
        val transcript = SqlDelightTranscriptStore.open(dbPath)
        val incarnationId = transcript.activeIncarnation().id
        val fluctuation = SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(amplitude = 0.04f, frequencyHz = 0.002f, phaseRadians = 0.1f)
                },
            ),
        )
        val initial = IncarnationStateStore.neutral(
            incarnationId,
            PersonaMode.GROWTH,
            PersonaSubState.PRE_COMMAND,
        ).copy(
            origin = BioVector.Neutral.apply(fluctuation.deltaBetween(0L, 1_000L)),
            lastRuntimeTickAtMs = 0L,
            lastVectorDynamicsAtMs = 0L,
        )
        SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            store.readOrCreate(incarnationId, PersonaMode.GROWTH, PersonaSubState.PRE_COMMAND)
            store.write(initial)
            VectorWriteService(
                incarnationStore = store,
                backgroundDynamicsReducer = BackgroundDynamicsReducer(
                    fluctuation = fluctuation,
                    omegaConfig = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f),
                    startedAtMs = 0L,
                ),
            ).commitIncarnationTurn(
                incarnationId = incarnationId,
                baseSnapshot = initial.vector,
                preTickedSnapshot = initial.vector,
                delta = io.openeden.bio.VectorDelta.Zero,
                shockSignal = null,
                lastUserActivityMs = null,
                reductionAtMs = 1_000L,
            )
        }

        SqlDelightIncarnationStateStore.open(dbPath).use { reopened ->
            val reducer = BackgroundDynamicsReducer(
                fluctuation = fluctuation,
                omegaConfig = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f),
                startedAtMs = 0L,
            )
            RuntimeTickScheduler(
                store = MutableSessionStateStore(),
                writer = VectorWriteService(
                    incarnationStore = reopened,
                    backgroundDynamicsReducer = reducer,
                ),
                fluctuation = fluctuation,
                inferenceExecutor = DirectInferenceExecutor,
                config = RuntimeConfig.Default.copy(
                    omega = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f),
                ),
                startedAtMs = 0L,
                incarnationStore = reopened,
                transcriptStore = transcript,
            ).evaluateOnce(nowMs = 2_000L)

            val state = reopened.read(incarnationId)
            assertEquals(
                initial.vector
                    .apply(fluctuation.deltaBetween(0L, 1_000L))
                    .apply(fluctuation.deltaBetween(1_000L, 2_000L)),
                state.vector,
            )
            assertEquals(2_000L, state.lastVectorDynamicsAtMs)
        }
        transcript.close()
    }

    @Test
    fun `independent production dynamics constructions preserve fluctuation coordinates across restart`() = runTest {
        val transcript = SqlDelightTranscriptStore.open(dbPath)
        val incarnationId = transcript.activeIncarnation().id
        val omegaConfig = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f)
        val firstStartedAtMs = 1_700_000_000_000L
        val turnAtMs = firstStartedAtMs + 1_000L
        val tickAtMs = firstStartedAtMs + 2_000L
        val firstFactory = IncarnationBackgroundDynamicsReducerFactory(omegaConfig)
        val initial = IncarnationStateStore.neutral(
            incarnationId,
            PersonaMode.GROWTH,
            PersonaSubState.PRE_COMMAND,
        ).copy(
            lastRuntimeTickAtMs = firstStartedAtMs,
            lastVectorDynamicsAtMs = firstStartedAtMs,
        )
        val expectedAtTick = SqlDelightIncarnationStateStore.open(dbPath).use { store ->
            store.readOrCreate(incarnationId, PersonaMode.GROWTH, PersonaSubState.PRE_COMMAND)
            store.write(initial)
            val afterTurn = VectorWriteService(
                incarnationStore = store,
                backgroundDynamicsReducerFactory = firstFactory::create,
            ).commitIncarnationTurn(
                incarnationId = incarnationId,
                baseSnapshot = initial.vector,
                preTickedSnapshot = initial.vector,
                delta = io.openeden.bio.VectorDelta.Zero,
                shockSignal = null,
                lastUserActivityMs = null,
                reductionAtMs = turnAtMs,
            ).state
            DirectInferenceExecutor.run {
                firstFactory.create(incarnationId).reduce(
                    vector = afterTurn.vector,
                    shockState = afterTurn.shockState,
                    omega = afterTurn.omega,
                    previousAtMs = turnAtMs,
                    throughAtMs = tickAtMs,
                )
            }
        }

        val secondFactory = IncarnationBackgroundDynamicsReducerFactory(omegaConfig)
        SqlDelightIncarnationStateStore.open(dbPath).use { reopened ->
            RuntimeTickScheduler(
                store = MutableSessionStateStore(),
                writer = VectorWriteService(
                    incarnationStore = reopened,
                    backgroundDynamicsReducerFactory = secondFactory::create,
                ),
                inferenceExecutor = DirectInferenceExecutor,
                config = RuntimeConfig.Default.copy(omega = omegaConfig),
                startedAtMs = 0L,
                incarnationStore = reopened,
                transcriptStore = transcript,
            ).evaluateOnce(nowMs = tickAtMs)

            val state = reopened.read(incarnationId)
            assertEquals(expectedAtTick.vector, state.vector)
            assertEquals(tickAtMs, state.lastVectorDynamicsAtMs)
        }
        transcript.close()
    }

    @Test
    fun `fresh incarnation construction replaces the fluctuation coordinate identity`() = runTest {
        val factory = IncarnationBackgroundDynamicsReducerFactory(
            OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f),
        )
        suspend fun vectorAfterInterval(incarnationId: String): BioVector = DirectInferenceExecutor.run {
            factory.create(incarnationId).reduce(
                vector = BioVector.Neutral,
                shockState = null,
                omega = OmegaState(0.0f),
                previousAtMs = 1_700_000_000_000L,
                throughAtMs = 1_700_000_060_000L,
            ).vector
        }

        assertNotEquals(
            vectorAfterInterval("incarnation-before-reset"),
            vectorAfterInterval("incarnation-after-reset"),
        )
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
            lastVectorDynamicsAtMs = 1_700_000_323_456L,
            centroidRevision = 12L,
            originRevision = 11L,
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

    @Test
    fun `atomic turn commit persists post commit plan and stage checkpoints`() = runTest {
        val transcript = SqlDelightTranscriptStore.open(dbPath)
        val incarnation = transcript.activeIncarnation()
        val plan = TurnPostCommitPlan(
            turnId = "durable-side-effects",
            relationshipEvaluation = RelationshipEvaluation(emptyList(), 1.0f),
        )
        val turn = ConversationTurn(
            turnId = plan.turnId,
            incarnationId = incarnation.id,
            sessionId = "QQ:group-42",
            platform = "QQ",
            scopeId = "group-42",
            userId = "owner",
            userText = "hello",
            assistantText = "response",
            completedAtMs = 1_000L,
        )
        val stateDispatcher = newSqliteDispatcher("post-commit-state-test")
        val stateStore = SqlDelightIncarnationStateStore.open(
            dbPath = dbPath,
            ioDispatcher = stateDispatcher,
            committedTranscriptStore = transcript,
        )
        try {
            val initial = stateStore.readOrCreate(
                incarnation.id,
                PersonaMode.GROWTH,
                PersonaSubState.PRE_COMMAND,
            )
            assertEquals(
                TurnCommitOutcome.INSERTED,
                stateStore.writeCommittedTurn(initial.copy(evolutionIndex = 1L), turn, plan),
            )
            assertEquals(plan, transcript.postCommitState(turn.turnId)?.plan)
            transcript.markPostCommitStageCompleted(turn.turnId, TurnPostCommitStage.RELATIONSHIP)
            assertEquals(
                setOf(TurnPostCommitStage.RELATIONSHIP),
                transcript.postCommitState(turn.turnId)?.completedStages,
            )
        } finally {
            stateStore.shutdown()
        }
        transcript.close()
    }

    @Test
    fun `legacy committed turn atomically adopts retry plan without rewriting Bio`() = runTest {
        val transcript = SqlDelightTranscriptStore.open(dbPath)
        val incarnation = transcript.activeIncarnation()
        val plan = TurnPostCommitPlan(turnId = "legacy-durable-side-effects")
        val turn = ConversationTurn(
            turnId = plan.turnId,
            incarnationId = incarnation.id,
            sessionId = "QQ:group-42",
            platform = "QQ",
            scopeId = "group-42",
            userId = "owner",
            userText = "hello",
            assistantText = "persisted response",
            completedAtMs = 1_000L,
        )
        transcript.append(turn)
        val stateStore = SqlDelightIncarnationStateStore.open(
            dbPath = dbPath,
            ioDispatcher = newSqliteDispatcher("legacy-post-commit-state-test"),
            committedTranscriptStore = transcript,
        )
        try {
            val persisted = stateStore.readOrCreate(
                incarnation.id,
                PersonaMode.GROWTH,
                PersonaSubState.PRE_COMMAND,
            ).copy(evolutionIndex = 7L)
            stateStore.write(persisted)

            assertEquals(
                TurnCommitOutcome.ALREADY_COMMITTED,
                stateStore.writeCommittedTurn(
                    persisted.copy(evolutionIndex = 8L),
                    turn.copy(completedAtMs = 2_000L),
                    plan,
                ),
            )

            assertEquals(persisted, stateStore.read(incarnation.id))
            assertEquals(plan, transcript.postCommitState(turn.turnId)?.plan)
            assertEquals(emptyList(), transcript.postCommitState(turn.turnId)?.pendingStages)
        } finally {
            stateStore.shutdown()
            transcript.close()
        }
    }

    private fun openVersion11DatabaseWithTwoSessionStates() {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            createLegacyMemoryEntries(driver, includesLineage = true)
            createPreV13DiaryTasks(driver)
            createLegacyRelationshipState(driver)
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

    private fun openVersionNineteenEstablishedState(lastRuntimeTickAtMs: Long) {
        val vectorJson = Json.encodeToString(BioVector.serializer(), BioVector.Neutral)
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            createPreV21OwnedSessionTables(driver)
            createPreV21PromptHistoryTables(driver)
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
                    lifecycle_request_id TEXT,
                    vector_json TEXT,
                    origin_json TEXT,
                    omega REAL,
                    evolution_index INTEGER,
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
                    singleton_id, active_incarnation_id, created_at_ms, lifecycle_status,
                    lifecycle_changed_at_ms, vector_json, origin_json, omega, evolution_index,
                    persona_mode, persona_start_sub_state, last_runtime_tick_at_ms
                ) VALUES (1, ?, 0, 'ACTIVE', 0, ?, ?, 0.0, 7, 'growth', 'pre_command', ?)
                """.trimIndent(),
                4,
            ) {
                bindString(0, activeIncarnationId)
                bindString(1, vectorJson)
                bindString(2, vectorJson)
                bindLong(3, lastRuntimeTickAtMs)
            }
            driver.execute(null, "PRAGMA user_version = 19", 0)
        }
    }

    private fun openVersionFourDatabase() {
        JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { driver ->
            createLegacyMemoryEntries(driver)
            createPreV13DiaryTasks(driver)
            createLegacyRelationshipState(driver)
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
            val vector = BioVector(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
            val vectorJson = Json.encodeToString(BioVector.serializer(), vector)
            driver.execute(
                null,
                """
                INSERT INTO session_state(
                    session_id, vector_json, origin_json, omega, evolution_index, last_user_activity_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
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
            createLegacyMemoryEntries(driver, includesLineage = true)
            createPreV13DiaryTasks(driver)
            createLegacyRelationshipState(driver)
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

    private fun createLegacyMemoryEntries(
        driver: JdbcSqliteDriver,
        includesLineage: Boolean = false,
    ) {
        val lineageColumns = if (includesLineage) {
            """
                ,source_turn_ids_json TEXT NOT NULL DEFAULT '[]'
                ,source_memory_ids_json TEXT NOT NULL DEFAULT '[]'
                ,content_fingerprint TEXT
                ,lineage_version INTEGER NOT NULL DEFAULT 1
            """.trimIndent()
        } else {
            ""
        }
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
                $lineageColumns
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

    private inline fun <T> SqlDelightIncarnationStateStore.use(block: (SqlDelightIncarnationStateStore) -> T): T {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}
