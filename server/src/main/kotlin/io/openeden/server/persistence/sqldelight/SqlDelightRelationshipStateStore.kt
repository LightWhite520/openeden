package io.openeden.server.persistence.sqldelight

import io.openeden.server.db.Database
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.relationship.RelationshipState
import io.openeden.relationship.RelationshipStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class SqlDelightRelationshipStateStore(
    private val database: Database,
    private val driver: SqlDriver,
) : RelationshipStateStore {
    private val queries get() = database.relationshipQueries

    override suspend fun readOrCreate(sessionId: String, userId: String, nowMs: Long): RelationshipState =
        withContext(Dispatchers.IO) {
            queries.selectByKey(sessionId, userId, ::map).executeAsOneOrNull()
                ?: RelationshipState.neutral(sessionId, userId, nowMs)
        }

    override suspend fun write(state: RelationshipState) {
        withContext(Dispatchers.IO) {
            queries.upsert(
                session_id = state.sessionId,
                user_id = state.userId,
                trust = state.trust.toDouble(),
                familiarity = state.familiarity.toDouble(),
                safety = state.safety.toDouble(),
                boundary_sensitivity = state.boundarySensitivity.toDouble(),
                unresolved_tension = state.unresolvedTension.toDouble(),
                evidence_count = state.evidenceCount,
                updated_at_ms = state.updatedAtMs,
            )
        }
    }

    override suspend fun reset(sessionId: String, userId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteByKey(sessionId, userId)
        }
    }

    fun close() = driver.close()

    private fun map(
        sessionId: String,
        userId: String,
        trust: Double,
        familiarity: Double,
        safety: Double,
        boundarySensitivity: Double,
        unresolvedTension: Double,
        evidenceCount: Long,
        updatedAtMs: Long,
    ): RelationshipState = RelationshipState(
        sessionId = sessionId,
        userId = userId,
        trust = trust.toFloat(),
        familiarity = familiarity.toFloat(),
        safety = safety.toFloat(),
        boundarySensitivity = boundarySensitivity.toFloat(),
        unresolvedTension = unresolvedTension.toFloat(),
        evidenceCount = evidenceCount,
        updatedAtMs = updatedAtMs,
    )

    companion object {
        fun open(dbPath: Path): SqlDelightRelationshipStateStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightRelationshipStateStore(Database(driver), driver)
        }
    }
}
