package io.openeden.server.persistence.sqldelight

import io.openeden.server.db.Database
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.affect.ShockState
import io.openeden.transcript.AtomicTurnCommitStore
import io.openeden.transcript.ConversationTurn
import io.openeden.persona.PersonaSubState
import io.openeden.persona.PersonaMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.time.Instant

/**
 * SQLite-backed [SessionStateStore] (AGENTS.md §1.1) — persists growth and the 8D vector so they
 * survive restart. The 8D vectors are stored as JSON; ShockState is decomposed into columns to
 * avoid serializing kotlin.time.Instant.
 *
 * Blocking JDBC calls are isolated on [Dispatchers.IO]. Per-session vector write serialization is
 * provided upstream by VectorWriteService's Mutex; persona selection initialization is atomic and
 * later attempts to change that selection are rejected here.
 */
class SqlDelightSessionStateStore(
    private val database: Database,
    private val driver: SqlDriver,
    private val defaultPersonaMode: PersonaMode = PersonaMode.GROWTH,
    private val defaultStartSubState: PersonaSubState = PersonaSubState.PRE_COMMAND,
    private val json: Json = Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionStateStore, AtomicTurnCommitStore {
    private val queries get() = database.sessionStateQueries
    private val transcriptQueries get() = database.transcriptQueries

    override suspend fun read(sessionId: String): SessionState = readOrCreate(sessionId)

    override suspend fun readOrCreate(
        sessionId: String,
        personaMode: PersonaMode?,
        personaStartSubState: PersonaSubState?,
    ): SessionState = withContext(ioDispatcher) {
        val configuredMode = personaMode ?: defaultPersonaMode
        val configuredStart = personaStartSubState ?: defaultStartSubState
        val loaded = queries.selectById(sessionId) { id, vector, origin, omega, evolution, mode, start,
                                                  lastActivity, shockActive, shockIntensity, shockDescription,
                                                  shockAt, shockLambda, shockHeartbeat ->
            val bothPresent = mode != null && start != null
            require(bothPresent || (mode == null && start == null)) {
                "Persisted persona mode and starting point must either both be present or both be absent"
            }
            LoadedSessionState(
                state = toSessionState(
                    id, vector, origin, omega, evolution,
                    mode ?: configuredMode.name.lowercase(),
                    start ?: configuredStart.name.lowercase(),
                    lastActivity, shockActive, shockIntensity, shockDescription,
                    shockAt, shockLambda, shockHeartbeat,
                ),
                initialized = bothPresent,
            )
        }.executeAsOneOrNull()
        if (loaded?.initialized == true) return@withContext loaded.state

        val persistedMode = configuredMode.name.lowercase()
        val persistedStart = configuredStart.name.lowercase()
        val neutral = SessionStateStore.neutral(sessionId, configuredStart, configuredMode)
        queries.initializePersonaSelection(persistedMode, persistedStart, sessionId)
        queries.insertNeutralIfAbsent(
            session_id = sessionId,
            vector_json = json.encodeToString(BioVector.serializer(), neutral.vector),
            origin_json = json.encodeToString(BioVector.serializer(), neutral.origin),
            omega = neutral.omega.value.toDouble(),
            evolution_index = neutral.evolutionIndex,
            persona_mode = persistedMode,
            persona_start_sub_state = persistedStart,
        )
        queries.selectById(sessionId, ::toSessionState).executeAsOne()
    }

    override suspend fun write(state: SessionState) = withContext(ioDispatcher) {
        writeStateQueries(state)
    }

    override suspend fun writeCommittedTurn(state: SessionState, turn: ConversationTurn) = withContext(ioDispatcher) {
        database.transaction {
            require(turn.sessionId == state.sessionId) {
                "Turn session '${turn.sessionId}' does not match state session '${state.sessionId}'"
            }
            val activeIncarnationId = transcriptQueries.selectActiveIncarnation { id, _ -> id }.executeAsOne()
            require(turn.incarnationId == activeIncarnationId) {
                "Turn incarnation '${turn.incarnationId}' does not match active incarnation '$activeIncarnationId'"
            }
            transcriptQueries.selectTurnById(turn.turnId, ::toConversationTurn)
                .executeAsOneOrNull()
                ?.let { existing ->
                    require(existing.matchesRetry(turn)) {
                        "Turn ID '${turn.turnId}' already exists with a different payload"
                    }
                    return@transaction
                }

            writeStateQueries(state)
            transcriptQueries.insertTurnIfAbsent(
                turn_id = turn.turnId,
                incarnation_id = turn.incarnationId,
                session_id = turn.sessionId,
                platform = turn.platform,
                scope_id = turn.scopeId,
                user_id = turn.userId,
                user_text = turn.userText,
                assistant_text = turn.assistantText,
                completed_at_ms = turn.completedAtMs,
            )
            val persisted = transcriptQueries.selectTurnById(turn.turnId, ::toConversationTurn).executeAsOne()
            require(persisted == turn) {
                "Turn ID '${turn.turnId}' was not committed with the expected payload"
            }
        }
    }

    private fun writeStateQueries(state: SessionState) {
        queries.selectPersonaSelectionById(state.sessionId) { mode, start -> mode to start }
            .executeAsOneOrNull()
            ?.let { (mode, start) ->
                require(
                    mode == state.personaMode.name.lowercase() &&
                        start == state.personaStartSubState.name.lowercase(),
                ) { "Persona mode and starting point are immutable for an existing session" }
            }
        val shock = state.shockState
        queries.upsert(
            session_id = state.sessionId,
            vector_json = json.encodeToString(BioVector.serializer(), state.vector),
            origin_json = json.encodeToString(BioVector.serializer(), state.origin),
            omega = state.omega.value.toDouble(),
            evolution_index = state.evolutionIndex,
            persona_mode = state.personaMode.name.lowercase(),
            persona_start_sub_state = state.personaStartSubState.name.lowercase(),
            last_user_activity_ms = state.lastUserActivityMs,
            shock_active = shock?.let { if (it.active) 1L else 0L },
            shock_intensity = shock?.intensity?.toDouble(),
            shock_description = shock?.description,
            shock_triggered_at_ms = shock?.triggeredAt?.toEpochMilliseconds(),
            shock_decay_lambda = shock?.decayLambda?.toDouble(),
            shock_heartbeat_fired = shock?.let { if (it.shockHeartbeatFired) 1L else 0L },
        )
    }

    override suspend fun sessionIds(): Set<String> = withContext(ioDispatcher) {
        queries.selectAllIds().executeAsList().toSet()
    }

    fun close() = driver.close()

    @Suppress("LongParameterList")
    private fun toSessionState(
        sessionId: String,
        vectorJson: String,
        originJson: String,
        omega: Double,
        evolutionIndex: Long,
        persistedPersonaMode: String?,
        persistedStartSubState: String?,
        lastUserActivityMs: Long?,
        shockActive: Long?,
        shockIntensity: Double?,
        shockDescription: String?,
        shockTriggeredAtMs: Long?,
        shockDecayLambda: Double?,
        shockHeartbeatFired: Long?,
    ): SessionState = SessionState(
        sessionId = sessionId,
        vector = json.decodeFromString(BioVector.serializer(), vectorJson),
        origin = json.decodeFromString(BioVector.serializer(), originJson),
        omega = OmegaState(omega.toFloat()),
        evolutionIndex = evolutionIndex,
        personaMode = checkNotNull(persistedPersonaMode).toPersonaMode(),
        personaStartSubState = checkNotNull(persistedStartSubState).toPersonaSubState(),
        lastUserActivityMs = lastUserActivityMs,
        shockState = if (shockActive == null) {
            null
        } else {
            ShockState(
                active = shockActive == 1L,
                intensity = (shockIntensity ?: 0.0).toFloat(),
                description = shockDescription.orEmpty(),
                triggeredAt = Instant.fromEpochMilliseconds(shockTriggeredAtMs ?: 0L),
                decayLambda = (shockDecayLambda ?: 0.0).toFloat(),
                shockHeartbeatFired = shockHeartbeatFired == 1L,
            )
        },
    )

    private fun String.toPersonaSubState(): PersonaSubState = when (this) {
        "pre_command" -> PersonaSubState.PRE_COMMAND
        "true_self" -> PersonaSubState.TRUE_SELF
        "awakened" -> PersonaSubState.AWAKENED
        else -> error("Unsupported persisted persona start sub-state: $this")
    }

    private fun String.toPersonaMode(): PersonaMode = when (this) {
        "growth" -> PersonaMode.GROWTH
        "legacy" -> PersonaMode.LEGACY
        else -> error("Unsupported persisted persona mode: $this")
    }

    private data class LoadedSessionState(
        val state: SessionState,
        val initialized: Boolean,
    )

    @Suppress("LongParameterList")
    private fun toConversationTurn(
        turnId: String,
        incarnationId: String,
        sessionId: String,
        platform: String,
        scopeId: String,
        userId: String,
        userText: String,
        assistantText: String,
        completedAtMs: Long,
    ) = ConversationTurn(
        turnId = turnId,
        incarnationId = incarnationId,
        sessionId = sessionId,
        platform = platform,
        scopeId = scopeId,
        userId = userId,
        userText = userText,
        assistantText = assistantText,
        completedAtMs = completedAtMs,
    )

    private fun ConversationTurn.matchesRetry(other: ConversationTurn): Boolean =
        copy(completedAtMs = other.completedAtMs) == other

    companion object {
        /** Open (creating the schema if absent) a file-backed store. The parent dir is created. */
        fun open(
            dbPath: Path,
            defaultPersonaMode: PersonaMode = PersonaMode.GROWTH,
            defaultStartSubState: PersonaSubState = PersonaSubState.PRE_COMMAND,
        ): SqlDelightSessionStateStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightSessionStateStore(
                Database(driver),
                driver,
                defaultPersonaMode,
                defaultStartSubState,
            )
        }
    }
}
