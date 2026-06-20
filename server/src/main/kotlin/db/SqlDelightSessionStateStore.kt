package io.openeden.server.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.runtime.OmegaState
import io.openeden.runtime.SessionState
import io.openeden.runtime.SessionStateStore
import io.openeden.runtime.ShockState
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
 * Per-session write serialization is provided upstream by VectorWriteService's Mutex; this store is
 * a thin row mapper.
 */
class SqlDelightSessionStateStore(
    private val database: Database,
    private val driver: SqlDriver,
    private val json: Json = Json,
) : SessionStateStore {
    private val queries get() = database.sessionStateQueries

    override suspend fun read(sessionId: String): SessionState = readOrCreate(sessionId)

    override suspend fun readOrCreate(sessionId: String): SessionState =
        queries.selectById(sessionId, ::toSessionState).executeAsOneOrNull()
            ?: SessionStateStore.neutral(sessionId)

    override suspend fun write(state: SessionState) {
        val shock = state.shockState
        queries.upsert(
            session_id = state.sessionId,
            vector_json = json.encodeToString(BioVector.serializer(), state.vector),
            origin_json = json.encodeToString(BioVector.serializer(), state.origin),
            omega = state.omega.value.toDouble(),
            evolution_index = state.evolutionIndex,
            last_user_activity_ms = state.lastUserActivityMs,
            shock_active = shock?.let { if (it.active) 1L else 0L },
            shock_intensity = shock?.intensity?.toDouble(),
            shock_description = shock?.description,
            shock_triggered_at_ms = shock?.triggeredAt?.toEpochMilliseconds(),
            shock_decay_lambda = shock?.decayLambda?.toDouble(),
            shock_heartbeat_fired = shock?.let { if (it.shockHeartbeatFired) 1L else 0L },
        )
    }

    override suspend fun sessionIds(): Set<String> = queries.selectAllIds().executeAsList().toSet()

    fun close() = driver.close()

    @Suppress("LongParameterList")
    private fun toSessionState(
        sessionId: String,
        vectorJson: String,
        originJson: String,
        omega: Double,
        evolutionIndex: Long,
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

    companion object {
        /** Open (creating the schema if absent) a file-backed store. The parent dir is created. */
        fun open(dbPath: Path): SqlDelightSessionStateStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightSessionStateStore(Database(driver), driver)
        }
    }
}
