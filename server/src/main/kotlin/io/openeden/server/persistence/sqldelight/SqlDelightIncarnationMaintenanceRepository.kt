package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.server.db.Database
import io.openeden.server.maintenance.IncarnationExportIntegrity
import io.openeden.server.maintenance.IncarnationExportSnapshot
import io.openeden.server.maintenance.IncarnationExportSnapshotFile
import io.openeden.server.maintenance.IncarnationResetPhase
import io.openeden.server.maintenance.IncarnationResetRejectedException
import io.openeden.server.maintenance.IncarnationResetRejection
import io.openeden.server.maintenance.IncarnationResetResult
import io.openeden.server.maintenance.PreparedIncarnationReset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Base64
import java.util.Properties

class SqlDelightIncarnationMaintenanceRepository private constructor(
    private val connection: Connection,
    private val ioDispatcher: CoroutineDispatcher,
    private val json: Json = Json,
) {
    suspend fun activeIncarnationId(): String = withContext(ioDispatcher) { currentIncarnation().id }

    suspend fun activeIncarnationIdOrNull(): String? = withContext(ioDispatcher) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT active_incarnation_id FROM incarnation_state WHERE lifecycle_status = 'ACTIVE' ORDER BY active_incarnation_id",
            ).use { result ->
                val ids = buildList { while (result.next()) add(result.getString(1)) }
                ids.singleOrNull()
            }
        }
    }

    suspend fun schemaVersion(): Int = withContext(ioDispatcher) {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
    }

    suspend fun resetRecord(requestId: String): PreparedIncarnationReset? = withContext(ioDispatcher) {
        selectReset(requestId)
    }

    suspend fun completedReset(requestId: String): IncarnationResetResult? = withContext(ioDispatcher) {
        selectReset(requestId)?.takeIf { it.phase == IncarnationResetPhase.COMPLETED }?.toResult()
    }

    suspend fun incompleteResets(): List<PreparedIncarnationReset> = withContext(ioDispatcher) {
        connection.prepareStatement(
            "SELECT request_id FROM incarnation_reset_requests WHERE phase <> 'COMPLETED' ORDER BY prepared_at_ms, request_id",
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(checkNotNull(selectReset(result.getString(1))))
                }
            }
        }
    }

    suspend fun activeIncarnationCount(): Long = withContext(ioDispatcher) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM incarnation_state WHERE lifecycle_status = 'ACTIVE'").use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
    }

    internal suspend fun exportSnapshot(incarnationId: String): IncarnationExportSnapshot = withContext(ioDispatcher) {
        transaction {
            val current = currentIncarnation()
            require(current.id == incarnationId) {
                "Requested incarnation '$incarnationId' is stale; active incarnation is '${current.id}'"
            }
            require(current.lifecycle == IncarnationLifecycle.ACTIVE) { "Only the active incarnation can be exported" }
            snapshotLocked(incarnationId, recoveryRequestId = null)
        }
    }

    @Suppress("LongParameterList")
    suspend fun prepareReset(
        previousIncarnationId: String,
        requestId: String,
        manifestSha256: String,
        manifestPath: String,
        expectedPayloadSha256: String,
        freshIncarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
        confirmed: Boolean,
        preparedAtMs: Long,
    ): PreparedIncarnationReset = withContext(ioDispatcher) {
        transaction {
            selectReset(requestId)?.let { return@transaction it }
            val current = currentIncarnation()
            if (current.id != previousIncarnationId || current.lifecycle != IncarnationLifecycle.ACTIVE) {
                reject(IncarnationResetRejection.STALE_INCARNATION_ID, "Requested incarnation is no longer active")
            }
            val currentPayload = snapshotLocked(previousIncarnationId, recoveryRequestId = null).payloadSha256
            if (currentPayload != expectedPayloadSha256) {
                reject(
                    IncarnationResetRejection.EXPORTED_STATE_CHANGED,
                    "Persisted state changed after the completed export",
                )
            }
            connection.prepareStatement(
                "SELECT request_id FROM incarnation_reset_requests WHERE phase <> 'COMPLETED'",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next() && result.getString(1) != requestId) {
                        reject(
                            IncarnationResetRejection.REQUEST_ID_CONFLICT,
                            "Another reset request already requires resume",
                        )
                    }
                }
            }
            val projections = selectProjectionRows(previousIncarnationId)
            insertReset(
                PreparedIncarnationReset(
                    requestId = requestId,
                    previousIncarnationId = previousIncarnationId,
                    freshIncarnationId = freshIncarnationId,
                    manifestSha256 = manifestSha256,
                    manifestPath = manifestPath,
                    payloadSha256 = expectedPayloadSha256,
                    personaMode = personaMode,
                    personaStartSubState = personaStartSubState,
                    confirmed = confirmed,
                    phase = IncarnationResetPhase.PREPARED,
                    projectionModelIds = projections.mapTo(linkedSetOf()) { it.modelId },
                    preparedAtMs = preparedAtMs,
                    completedAtMs = null,
                ),
            )
            projections.forEach { projection ->
                insertProjectionRecovery(requestId, projection)
                connection.prepareStatement(
                    "UPDATE memory_vector_sync SET status = 'RESET_RECOVERABLE', last_error = ?, updated_at_ms = ? WHERE memory_id = ?",
                ).use { statement ->
                    statement.setString(1, "incarnation reset $requestId prepared")
                    statement.setLong(2, preparedAtMs)
                    statement.setString(3, projection.memoryId)
                    check(statement.executeUpdate() == 1)
                }
            }
            checkNotNull(selectReset(requestId))
        }
    }

    suspend fun markProjectionsVerified(requestId: String): PreparedIncarnationReset = withContext(ioDispatcher) {
        transaction {
            val current = checkNotNull(selectReset(requestId)) { "Reset request is not prepared" }
            if (current.phase == IncarnationResetPhase.PREPARED) {
                connection.prepareStatement(
                    "UPDATE incarnation_reset_requests SET phase = 'PROJECTIONS_VERIFIED' WHERE request_id = ? AND phase = 'PREPARED'",
                ).use { statement ->
                    statement.setString(1, requestId)
                    check(statement.executeUpdate() == 1)
                }
            }
            checkNotNull(selectReset(requestId))
        }
    }

    suspend fun completeReset(requestId: String, completedAtMs: Long): IncarnationResetResult = withContext(ioDispatcher) {
        transaction {
            val prepared = checkNotNull(selectReset(requestId)) { "Reset request is not prepared" }
            if (prepared.phase == IncarnationResetPhase.COMPLETED) return@transaction prepared.toResult()
            check(prepared.phase == IncarnationResetPhase.PROJECTIONS_VERIFIED) {
                "Projection erasure must be durably verified before final SQL reset"
            }
            val current = currentIncarnation()
            if (current.id != prepared.previousIncarnationId || current.lifecycle != IncarnationLifecycle.ACTIVE) {
                reject(IncarnationResetRejection.STALE_INCARNATION_ID, "Requested incarnation is no longer active")
            }
            val currentPayload = snapshotLocked(prepared.previousIncarnationId, requestId).payloadSha256
            if (currentPayload != prepared.payloadSha256) {
                reject(
                    IncarnationResetRejection.EXPORTED_STATE_CHANGED,
                    "Persisted state changed after reset preparation",
                )
            }

            eraseOwnedData(prepared.previousIncarnationId)
            check(ownedRowCount(prepared.previousIncarnationId) == 0L) {
                "Old incarnation rows remain after reset"
            }
            createFreshIncarnation(prepared, completedAtMs)
            connection.prepareStatement(
                "DELETE FROM incarnation_reset_projection_recovery WHERE request_id = ?",
            ).use { statement ->
                statement.setString(1, requestId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE incarnation_reset_requests SET phase = 'COMPLETED', completed_at_ms = ? WHERE request_id = ? AND phase = 'PROJECTIONS_VERIFIED'",
            ).use { statement ->
                statement.setLong(1, completedAtMs)
                statement.setString(2, requestId)
                check(statement.executeUpdate() == 1)
            }
            checkNotNull(selectReset(requestId)).toResult()
        }
    }

    suspend fun close() {
        withContext(ioDispatcher) { connection.close() }
        (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    private fun snapshotLocked(incarnationId: String, recoveryRequestId: String?): IncarnationExportSnapshot {
        val files = EXPORT_TABLES.map { table ->
            val bytes = if (table.tableName == "memory_vector_sync" && recoveryRequestId != null) {
                exportRecoveredProjectionTable(incarnationId, recoveryRequestId)
            } else {
                exportTable(table, incarnationId)
            }
            IncarnationExportSnapshotFile(table.fileName, bytes)
        }
        return IncarnationExportSnapshot(
            incarnationId = incarnationId,
            files = files,
            transcriptCount = countForIncarnation("conversation_turns", incarnationId),
            memoryCount = countForIncarnation("memory_entries", incarnationId),
            relationshipEventCount = countForIncarnation("relationship_events", incarnationId),
        )
    }

    private fun exportTable(table: ExportTable, incarnationId: String): ByteArray =
        connection.prepareStatement(table.selectSql).use { statement ->
            bindIncarnation(statement, table.parameterCount, incarnationId)
            statement.executeQuery().use { result -> result.toCanonicalJsonLines() }.encodeToByteArray()
        }

    private fun exportRecoveredProjectionTable(incarnationId: String, requestId: String): ByteArray =
        connection.prepareStatement(
            """
            SELECT sync.memory_id, sync.model_id,
                   COALESCE(recovery.previous_status, sync.status) AS status,
                   COALESCE(recovery.previous_attempts, sync.attempts) AS attempts,
                   COALESCE(recovery.previous_available_at_ms, sync.available_at_ms) AS available_at_ms,
                   CASE WHEN recovery.memory_id IS NULL THEN sync.last_error ELSE recovery.previous_last_error END AS last_error,
                   COALESCE(recovery.previous_updated_at_ms, sync.updated_at_ms) AS updated_at_ms
            FROM memory_vector_sync sync
            JOIN memory_entries memory ON memory.id = sync.memory_id
            LEFT JOIN incarnation_reset_projection_recovery recovery
              ON recovery.request_id = ? AND recovery.memory_id = sync.memory_id
            WHERE memory.incarnation_id = ?
            ORDER BY sync.memory_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, requestId)
            statement.setString(2, incarnationId)
            statement.executeQuery().use { result -> result.toCanonicalJsonLines() }.encodeToByteArray()
        }

    private fun ResultSet.toCanonicalJsonLines(): String {
        val columns = (1..metaData.columnCount)
            .map { index -> metaData.getColumnLabel(index) to index }
            .sortedBy { it.first }
        val rows = buildList {
            while (next()) {
                val values = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
                columns.forEach { (name, index) -> values[name] = getObject(index).toJsonValue() }
                add(JsonObject(values).toString())
            }
        }
        return if (rows.isEmpty()) "" else rows.joinToString("\n", postfix = "\n")
    }

    private fun Any?.toJsonValue(): kotlinx.serialization.json.JsonElement = when (this) {
        null -> JsonNull
        is ByteArray -> JsonPrimitive(Base64.getEncoder().encodeToString(this))
        is Byte, is Short, is Int, is Long -> JsonPrimitive((this as Number).toLong())
        is Float, is Double -> JsonPrimitive(BigDecimal(toString()))
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    private fun selectProjectionRows(incarnationId: String): List<ProjectionRow> =
        connection.prepareStatement(
            """
            SELECT sync.memory_id, sync.model_id, sync.status, sync.attempts,
                   sync.available_at_ms, sync.last_error, sync.updated_at_ms
            FROM memory_vector_sync sync
            JOIN memory_entries memory ON memory.id = sync.memory_id
            WHERE memory.incarnation_id = ?
            ORDER BY sync.memory_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, incarnationId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            ProjectionRow(
                                memoryId = result.getString(1),
                                modelId = result.getString(2),
                                status = result.getString(3),
                                attempts = result.getLong(4),
                                availableAtMs = result.getLong(5),
                                lastError = result.getString(6),
                                updatedAtMs = result.getLong(7),
                            ),
                        )
                    }
                }
            }
        }

    private fun insertProjectionRecovery(requestId: String, row: ProjectionRow) {
        connection.prepareStatement(
            """
            INSERT INTO incarnation_reset_projection_recovery(
                request_id, memory_id, model_id, previous_status, previous_attempts,
                previous_available_at_ms, previous_last_error, previous_updated_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, requestId)
            statement.setString(2, row.memoryId)
            statement.setString(3, row.modelId)
            statement.setString(4, row.status)
            statement.setLong(5, row.attempts)
            statement.setLong(6, row.availableAtMs)
            statement.setString(7, row.lastError)
            statement.setLong(8, row.updatedAtMs)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun eraseOwnedData(incarnationId: String) {
        DELETE_STATEMENTS.forEach { sql ->
            connection.prepareStatement(sql).use { statement ->
                bindIncarnation(statement, sql.count { it == '?' }, incarnationId)
                statement.executeUpdate()
            }
        }
    }

    private fun ownedRowCount(incarnationId: String): Long = OWNED_COUNT_QUERIES.sumOf { sql ->
        connection.prepareStatement(sql).use { statement ->
            bindIncarnation(statement, sql.count { it == '?' }, incarnationId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
    }

    private fun createFreshIncarnation(prepared: PreparedIncarnationReset, completedAtMs: Long) {
        val neutralJson = json.encodeToString(BioVector.serializer(), BioVector.Neutral)
        connection.prepareStatement(
            """
            UPDATE incarnation_state
            SET active_incarnation_id = ?, created_at_ms = ?, lifecycle_status = 'ACTIVE',
                lifecycle_changed_at_ms = ?, termination_reason = NULL, lifecycle_request_id = ?,
                vector_json = ?, origin_json = ?, omega = 0.0, evolution_index = 0,
                persona_mode = ?, persona_start_sub_state = ?, last_user_activity_ms = NULL,
                last_runtime_tick_at_ms = NULL, shock_active = NULL, shock_intensity = NULL,
                shock_description = NULL, shock_triggered_at_ms = NULL, shock_decay_lambda = NULL,
                shock_heartbeat_fired = NULL, last_vector_dynamics_at_ms = NULL,
                centroid_revision = 0, origin_revision = 0
            WHERE singleton_id = 1 AND active_incarnation_id = ? AND lifecycle_status = 'ACTIVE'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, prepared.freshIncarnationId)
            statement.setLong(2, completedAtMs)
            statement.setLong(3, completedAtMs)
            statement.setString(4, prepared.requestId)
            statement.setString(5, neutralJson)
            statement.setString(6, neutralJson)
            statement.setString(7, prepared.personaMode.persistedName())
            statement.setString(8, prepared.personaStartSubState.persistedName())
            statement.setString(9, prepared.previousIncarnationId)
            check(statement.executeUpdate() == 1) { "Expected exactly one active incarnation singleton row" }
        }
    }

    private fun insertReset(reset: PreparedIncarnationReset) {
        connection.prepareStatement(
            """
            INSERT INTO incarnation_reset_requests(
                request_id, previous_incarnation_id, fresh_incarnation_id, manifest_sha256, manifest_path,
                payload_sha256, persona_mode, persona_start_sub_state, confirmed, phase,
                projection_models_json, prepared_at_ms, completed_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, reset.requestId)
            statement.setString(2, reset.previousIncarnationId)
            statement.setString(3, reset.freshIncarnationId)
            statement.setString(4, reset.manifestSha256)
            statement.setString(5, reset.manifestPath)
            statement.setString(6, reset.payloadSha256)
            statement.setString(7, reset.personaMode.persistedName())
            statement.setString(8, reset.personaStartSubState.persistedName())
            statement.setInt(9, if (reset.confirmed) 1 else 0)
            statement.setString(10, reset.phase.name)
            statement.setString(11, json.encodeToString(reset.projectionModelIds.sorted()))
            statement.setLong(12, reset.preparedAtMs)
            statement.setObject(13, reset.completedAtMs)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun selectReset(requestId: String): PreparedIncarnationReset? =
        connection.prepareStatement(
            """
            SELECT previous_incarnation_id, fresh_incarnation_id, manifest_sha256, manifest_path, payload_sha256,
                   persona_mode, persona_start_sub_state, confirmed, phase, projection_models_json,
                   prepared_at_ms, completed_at_ms
            FROM incarnation_reset_requests WHERE request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, requestId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                PreparedIncarnationReset(
                    requestId = requestId,
                    previousIncarnationId = result.getString(1),
                    freshIncarnationId = result.getString(2),
                    manifestSha256 = result.getString(3),
                    manifestPath = result.getString(4),
                    payloadSha256 = result.getString(5),
                    personaMode = result.getString(6).toPersonaMode(),
                    personaStartSubState = result.getString(7).toPersonaSubState(),
                    confirmed = result.getInt(8) == 1,
                    phase = IncarnationResetPhase.valueOf(result.getString(9)),
                    projectionModelIds = json.decodeFromString<List<String>>(result.getString(10)).toSet(),
                    preparedAtMs = result.getLong(11),
                    completedAtMs = result.getLong(12).takeUnless { result.wasNull() },
                )
            }
        }

    private fun currentIncarnation(): CurrentIncarnation = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT active_incarnation_id, lifecycle_status FROM incarnation_state WHERE singleton_id = 1",
        ).use { result ->
            check(result.next()) { "No active incarnation singleton exists" }
            CurrentIncarnation(result.getString(1), IncarnationLifecycle.valueOf(result.getString(2)))
        }
    }

    private fun countForIncarnation(table: String, incarnationId: String): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE incarnation_id = ?").use { statement ->
            statement.setString(1, incarnationId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }

    private fun PreparedIncarnationReset.toResult(): IncarnationResetResult {
        check(phase == IncarnationResetPhase.COMPLETED && completedAtMs != null)
        return IncarnationResetResult(
            requestId = requestId,
            previousIncarnationId = previousIncarnationId,
            activeIncarnationId = freshIncarnationId,
            lifecycle = IncarnationLifecycle.ACTIVE,
            personaMode = personaMode,
            personaStartSubState = personaStartSubState,
            completedAtMs = completedAtMs,
        )
    }

    private fun <T> transaction(block: () -> T): T {
        check(connection.autoCommit) { "Nested maintenance transactions are not supported" }
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun bindIncarnation(statement: PreparedStatement, count: Int, incarnationId: String) {
        (1..count).forEach { statement.setString(it, incarnationId) }
    }

    private fun reject(reason: IncarnationResetRejection, message: String): Nothing =
        throw IncarnationResetRejectedException(reason, message)

    private fun PersonaMode.persistedName(): String = name.lowercase()
    private fun PersonaSubState.persistedName(): String = name.lowercase()

    private fun String.toPersonaMode(): PersonaMode = when (lowercase()) {
        "growth" -> PersonaMode.GROWTH
        "legacy" -> PersonaMode.LEGACY
        else -> error("Unsupported persisted persona mode: $this")
    }

    private fun String.toPersonaSubState(): PersonaSubState = when (lowercase()) {
        "pre_command" -> PersonaSubState.PRE_COMMAND
        "true_self" -> PersonaSubState.TRUE_SELF
        "awakened" -> PersonaSubState.AWAKENED
        else -> error("Unsupported persisted persona starting point: $this")
    }

    private data class CurrentIncarnation(val id: String, val lifecycle: IncarnationLifecycle)

    private data class ProjectionRow(
        val memoryId: String,
        val modelId: String,
        val status: String,
        val attempts: Long,
        val availableAtMs: Long,
        val lastError: String?,
        val updatedAtMs: Long,
    )

    private data class ExportTable(
        val fileName: String,
        val tableName: String,
        val selectSql: String,
        val parameterCount: Int = 1,
    )

    companion object {
        const val MINIMUM_SCHEMA_VERSION = 23

        private fun direct(file: String, table: String, order: String) = ExportTable(
            file,
            table,
            "SELECT * FROM $table WHERE incarnation_id = ? ORDER BY $order",
        )

        private val EXPORT_TABLES = listOf(
            ExportTable(
                "incarnation-state.jsonl",
                "incarnation_state",
                "SELECT * FROM incarnation_state WHERE active_incarnation_id = ? ORDER BY singleton_id",
            ),
            direct("transcript-turns.jsonl", "conversation_turns", "turn_id"),
            ExportTable(
                "turn-post-commit.jsonl",
                "turn_post_commit",
                "SELECT post.* FROM turn_post_commit post JOIN conversation_turns turn ON turn.turn_id = post.turn_id WHERE turn.incarnation_id = ? ORDER BY post.turn_id",
            ),
            direct("prompt-history-state.jsonl", "prompt_history_state", "session_id"),
            direct("prompt-history-chunks.jsonl", "prompt_history_chunks", "chunk_id"),
            direct("prompt-history-compactions.jsonl", "prompt_history_compactions", "request_id"),
            direct("legacy-session-state.jsonl", "session_state", "session_id"),
            direct("memory-entries.jsonl", "memory_entries", "id"),
            ExportTable(
                "memory-embeddings.jsonl",
                "memory_embeddings",
                "SELECT embedding.* FROM memory_embeddings embedding JOIN memory_entries memory ON memory.id = embedding.memory_id WHERE memory.incarnation_id = ? ORDER BY embedding.memory_id",
            ),
            ExportTable(
                "memory-vector-sync.jsonl",
                "memory_vector_sync",
                "SELECT sync.* FROM memory_vector_sync sync JOIN memory_entries memory ON memory.id = sync.memory_id WHERE memory.incarnation_id = ? ORDER BY sync.memory_id",
            ),
            direct("diary-tasks.jsonl", "diary_tasks", "id"),
            direct("diary-checkpoints.jsonl", "diary_checkpoints", "session_id"),
            direct("diary-archive.jsonl", "diary_archive", "archive_entry_id"),
            direct("relationship-state.jsonl", "relationship_state", "incarnation_id, canonical_subject_id"),
            direct("relationship-events.jsonl", "relationship_events", "event_id"),
            ExportTable(
                "trace-spans.jsonl",
                "trace_spans",
                "SELECT span.* FROM trace_spans span WHERE span.turn_id IN (SELECT turn_id FROM conversation_turns WHERE incarnation_id = ?) ORDER BY span.span_id",
            ),
        )

        private val DELETE_STATEMENTS = listOf(
            "DELETE FROM turn_post_commit WHERE turn_id IN (SELECT turn_id FROM conversation_turns WHERE incarnation_id = ?)",
            "DELETE FROM prompt_history_chunks WHERE incarnation_id = ?",
            "DELETE FROM prompt_history_compactions WHERE incarnation_id = ?",
            "DELETE FROM prompt_history_state WHERE incarnation_id = ?",
            "DELETE FROM session_state WHERE incarnation_id = ?",
            "DELETE FROM trace_spans WHERE turn_id IN (SELECT turn_id FROM conversation_turns WHERE incarnation_id = ?)",
            "DELETE FROM memory_vector_sync WHERE memory_id IN (SELECT id FROM memory_entries WHERE incarnation_id = ?)",
            "DELETE FROM memory_embeddings WHERE memory_id IN (SELECT id FROM memory_entries WHERE incarnation_id = ?)",
            "DELETE FROM diary_checkpoints WHERE incarnation_id = ?",
            "DELETE FROM diary_tasks WHERE incarnation_id = ?",
            "DELETE FROM memory_entries WHERE incarnation_id = ?",
            "DELETE FROM diary_archive WHERE incarnation_id = ?",
            "DELETE FROM relationship_events WHERE incarnation_id = ?",
            "DELETE FROM relationship_state WHERE incarnation_id = ?",
            "DELETE FROM conversation_turns WHERE incarnation_id = ?",
        )

        private val OWNED_COUNT_QUERIES = EXPORT_TABLES
            .filterNot { it.tableName == "incarnation_state" }
            .map { table -> "SELECT COUNT(*) FROM (${table.selectSql.substringBefore(" ORDER BY ")})" }

        fun open(
            dbPath: Path,
            ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-maintenance-sqlite"),
        ): SqlDelightIncarnationMaintenanceRepository {
            dbPath.parent?.let(Files::createDirectories)
            val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
            JdbcSqliteDriver(jdbcUrl, Properties(), Database.Schema).close()
            val connection = DriverManager.getConnection(jdbcUrl)
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
            return SqlDelightIncarnationMaintenanceRepository(connection, ioDispatcher)
        }
    }
}
