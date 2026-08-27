package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.incarnation.IncarnationState
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.server.db.Database
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.time.Instant

class SqlDelightIncarnationStateStore(
    private val database: Database,
    private val driver: SqlDriver,
    private val json: Json = Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IncarnationStateStore {
    private val queries get() = database.incarnationQueries

    override suspend fun read(incarnationId: String): IncarnationState = withContext(ioDispatcher) {
        queries.selectBioByIncarnationId(incarnationId, ::toIncarnationState)
            .executeAsOneOrNull()
            ?.let(::requireInitialized)
            ?: error("No Bio state exists for active incarnation '$incarnationId'")
    }

    override suspend fun readOrCreate(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState = withContext(ioDispatcher) {
        val existing = queries.selectBioByIncarnationId(incarnationId, ::toIncarnationState)
            .executeAsOneOrNull()
        if (existing != null && existing.vector != null) return@withContext requireInitialized(existing)

        val neutral = IncarnationStateStore.neutral(incarnationId, personaMode, personaStartSubState)
        writeBioIfAbsent(neutral)
        readInitialized(incarnationId)
    }

    override suspend fun write(state: IncarnationState) = withContext(ioDispatcher) {
        val current = readInitialized(state.incarnationId)
        require(
            current.personaMode == state.personaMode &&
                current.personaStartSubState == state.personaStartSubState,
        ) { "Persona mode and starting point are immutable for an existing incarnation" }
        updateBio(state)
    }

    fun close() = driver.close()

    private fun readInitialized(incarnationId: String): IncarnationState =
        queries.selectBioByIncarnationId(incarnationId, ::toIncarnationState)
            .executeAsOneOrNull()
            ?.let(::requireInitialized)
            ?: error("No Bio state exists for active incarnation '$incarnationId'")

    private fun writeBioIfAbsent(state: IncarnationState) {
        val shock = state.shockState
        queries.initializeBioIfAbsent(
            vector_json = json.encodeToString(BioVector.serializer(), state.vector),
            origin_json = json.encodeToString(BioVector.serializer(), state.origin),
            omega = state.omega.value.toDouble(),
            evolution_index = state.evolutionIndex,
            persona_mode = state.personaMode.persistedName(),
            persona_start_sub_state = state.personaStartSubState.persistedName(),
            last_user_activity_ms = state.lastUserActivityMs,
            last_runtime_tick_at_ms = state.lastRuntimeTickAtMs,
            shock_active = shock?.let { if (it.active) 1L else 0L },
            shock_intensity = shock?.intensity?.toDouble(),
            shock_description = shock?.description,
            shock_triggered_at_ms = shock?.triggeredAt?.toEpochMilliseconds(),
            shock_decay_lambda = shock?.decayLambda?.toDouble(),
            shock_heartbeat_fired = shock?.let { if (it.shockHeartbeatFired) 1L else 0L },
            active_incarnation_id = state.incarnationId,
        )
    }

    private fun updateBio(state: IncarnationState) {
        val shock = state.shockState
        queries.updateBio(
            vector_json = json.encodeToString(BioVector.serializer(), state.vector),
            origin_json = json.encodeToString(BioVector.serializer(), state.origin),
            omega = state.omega.value.toDouble(),
            evolution_index = state.evolutionIndex,
            persona_mode = state.personaMode.persistedName(),
            persona_start_sub_state = state.personaStartSubState.persistedName(),
            last_user_activity_ms = state.lastUserActivityMs,
            last_runtime_tick_at_ms = state.lastRuntimeTickAtMs,
            shock_active = shock?.let { if (it.active) 1L else 0L },
            shock_intensity = shock?.intensity?.toDouble(),
            shock_description = shock?.description,
            shock_triggered_at_ms = shock?.triggeredAt?.toEpochMilliseconds(),
            shock_decay_lambda = shock?.decayLambda?.toDouble(),
            shock_heartbeat_fired = shock?.let { if (it.shockHeartbeatFired) 1L else 0L },
            active_incarnation_id = state.incarnationId,
        )
    }

    @Suppress("LongParameterList")
    private fun toIncarnationState(
        incarnationId: String,
        vectorJson: String?,
        originJson: String?,
        omega: Double?,
        evolutionIndex: Long?,
        personaMode: String?,
        personaStartSubState: String?,
        lastUserActivityMs: Long?,
        lastRuntimeTickAtMs: Long?,
        shockActive: Long?,
        shockIntensity: Double?,
        shockDescription: String?,
        shockTriggeredAtMs: Long?,
        shockDecayLambda: Double?,
        shockHeartbeatFired: Long?,
    ) = LoadedIncarnationState(
        incarnationId = incarnationId,
        vector = vectorJson?.let { json.decodeFromString(BioVector.serializer(), it) },
        origin = originJson?.let { json.decodeFromString(BioVector.serializer(), it) },
        omega = omega?.let { OmegaState(it.toFloat()) },
        evolutionIndex = evolutionIndex,
        personaMode = personaMode?.toPersonaMode(),
        personaStartSubState = personaStartSubState?.toPersonaSubState(),
        lastUserActivityMs = lastUserActivityMs,
        lastRuntimeTickAtMs = lastRuntimeTickAtMs,
        shockState = shockActive?.let {
            ShockState(
                active = it == 1L,
                intensity = (shockIntensity ?: 0.0).toFloat(),
                description = shockDescription.orEmpty(),
                triggeredAt = Instant.fromEpochMilliseconds(shockTriggeredAtMs ?: 0L),
                decayLambda = (shockDecayLambda ?: 0.0).toFloat(),
                shockHeartbeatFired = shockHeartbeatFired == 1L,
            )
        },
    )

    private fun requireInitialized(state: LoadedIncarnationState): IncarnationState = IncarnationState(
        incarnationId = state.incarnationId,
        vector = checkNotNull(state.vector) { "Persisted Bio vector is missing" },
        origin = checkNotNull(state.origin) { "Persisted Bio origin is missing" },
        omega = checkNotNull(state.omega) { "Persisted Omega state is missing" },
        evolutionIndex = checkNotNull(state.evolutionIndex) { "Persisted evolution index is missing" },
        personaMode = checkNotNull(state.personaMode) { "Persisted persona mode is missing" },
        personaStartSubState = checkNotNull(state.personaStartSubState) { "Persisted persona start is missing" },
        lastUserActivityMs = state.lastUserActivityMs,
        lastRuntimeTickAtMs = state.lastRuntimeTickAtMs,
        shockState = state.shockState,
    )

    private fun PersonaMode.persistedName(): String = name.lowercase()

    private fun PersonaSubState.persistedName(): String = name.lowercase()

    private fun String.toPersonaMode(): PersonaMode = when (this) {
        "growth" -> PersonaMode.GROWTH
        "legacy" -> PersonaMode.LEGACY
        else -> error("Unsupported persisted persona mode: $this")
    }

    private fun String.toPersonaSubState(): PersonaSubState = when (this) {
        "pre_command" -> PersonaSubState.PRE_COMMAND
        "true_self" -> PersonaSubState.TRUE_SELF
        "awakened" -> PersonaSubState.AWAKENED
        else -> error("Unsupported persisted persona start sub-state: $this")
    }

    private data class LoadedIncarnationState(
        val incarnationId: String,
        val vector: BioVector?,
        val origin: BioVector?,
        val omega: OmegaState?,
        val evolutionIndex: Long?,
        val personaMode: PersonaMode?,
        val personaStartSubState: PersonaSubState?,
        val lastUserActivityMs: Long?,
        val lastRuntimeTickAtMs: Long?,
        val shockState: ShockState?,
    )

    companion object {
        fun open(
            dbPath: Path,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SqlDelightIncarnationStateStore {
            dbPath.parent?.let(Files::createDirectories)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightIncarnationStateStore(Database(driver), driver, ioDispatcher = ioDispatcher)
        }
    }
}
