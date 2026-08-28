package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.relationship.RelationshipCorrection
import io.openeden.relationship.RelationshipContinuousAccumulator
import io.openeden.relationship.RelationshipEvent
import io.openeden.relationship.RelationshipEventType
import io.openeden.relationship.RelationshipFacts
import io.openeden.relationship.RelationshipPhase
import io.openeden.relationship.RelationshipReducer
import io.openeden.relationship.RelationshipState
import io.openeden.relationship.RelationshipStateStore
import io.openeden.server.db.Database
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class SqlDelightRelationshipStateStore private constructor(
    private val database: Database,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : RelationshipStateStore {
    private val queries get() = database.relationshipQueries

    override suspend fun readOrCreate(
        incarnationId: String,
        canonicalSubjectId: String,
        nowMs: Long,
    ): RelationshipState = withContext(ioDispatcher) {
        read(incarnationId, canonicalSubjectId)
            ?: RelationshipState.neutral(incarnationId, canonicalSubjectId, nowMs)
    }

    override suspend fun write(state: RelationshipState) = withContext(ioDispatcher) {
        database.transaction {
            state.events.forEach(::insertEvent)
            writeSnapshot(state)
        }
    }

    override suspend fun append(event: RelationshipEvent): RelationshipState = withContext(ioDispatcher) {
        database.transactionWithResult {
            val current = stateForReplay(
                incarnationId = event.incarnationId,
                canonicalSubjectId = event.canonicalSubjectId,
                nowMs = event.createdAtMs,
            )
            val isNew = current.events.none { it.idempotencyKey() == event.idempotencyKey() }
            insertEvent(event)
            val reduced = RelationshipReducer.reduce(current, if (isNew) listOf(event) else emptyList())
            writeSnapshot(reduced)
            reduced
        }
    }

    override suspend fun events(incarnationId: String, canonicalSubjectId: String): List<RelationshipEvent> =
        withContext(ioDispatcher) {
            queries.selectEvents(incarnationId, canonicalSubjectId, ::toEvent).executeAsList()
        }

    override suspend fun correct(correction: RelationshipCorrection): RelationshipState = append(correction.event)

    override suspend fun reset(
        incarnationId: String,
        canonicalSubjectId: String,
        sourceTurnId: String,
        eventId: String,
        createdAtMs: Long,
    ): RelationshipState = super<RelationshipStateStore>.reset(
        incarnationId,
        canonicalSubjectId,
        sourceTurnId,
        eventId,
        createdAtMs,
    )

    suspend fun close() = withContext(ioDispatcher) {
        if (driver is JdbcSqliteDriver) driver.closeCurrentThreadConnection()
        driver.close()
        (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    private fun read(incarnationId: String, canonicalSubjectId: String): RelationshipState? {
        val state = queries.selectByIdentity(incarnationId, canonicalSubjectId, ::toState).executeAsOneOrNull()
            ?: return null
        return RelationshipReducer.reduce(stateForReplay(incarnationId, canonicalSubjectId), emptyList())
    }

    private fun stateForReplay(
        incarnationId: String,
        canonicalSubjectId: String,
        nowMs: Long = 0L,
    ): RelationshipState {
        val state = queries.selectByIdentity(incarnationId, canonicalSubjectId, ::toState).executeAsOneOrNull()
            ?: RelationshipState.neutral(incarnationId, canonicalSubjectId, nowMs)
        val events = queries.selectEvents(incarnationId, canonicalSubjectId, ::toEvent).executeAsList()
        return state.copy(events = events)
    }

    private fun insertEvent(event: RelationshipEvent) {
        queries.insertEventIfAbsent(
            event_id = event.eventId,
            incarnation_id = event.incarnationId,
            canonical_subject_id = event.canonicalSubjectId,
            source_turn_id = event.sourceTurnId,
            event_type = event.type.name,
            confidence = event.confidence.toDouble(),
            evidence_digest = event.evidenceDigest,
            created_at_ms = event.createdAtMs,
            supersedes_event_id = event.supersedesEventId,
            preferred_address = event.preferredAddress,
        )
    }

    private fun writeSnapshot(state: RelationshipState) {
        val accumulator = state.continuousAccumulator ?: RelationshipContinuousAccumulator.from(state)
        queries.upsert(
            incarnation_id = state.incarnationId,
            canonical_subject_id = state.canonicalSubjectId,
            trust = state.trust.toDouble(),
            familiarity = state.familiarity.toDouble(),
            safety = state.safety.toDouble(),
            boundary_sensitivity = state.boundarySensitivity.toDouble(),
            unresolved_tension = state.unresolvedTension.toDouble(),
            reciprocal_interest = state.reciprocalInterest.toDouble(),
            evidence_count = state.evidenceCount,
            updated_at_ms = state.updatedAtMs,
            phase = state.facts.phase.name,
            user_confessed_at_ms = state.facts.userConfessedAtMs,
            atri_accepted_at_ms = state.facts.atriAcceptedAtMs,
            mutual_commitment_at_ms = state.facts.mutualCommitmentAtMs,
            preferred_addresses_json = json.encodeToString(state.facts.preferredAddresses.toList()),
            continuous_accumulator_version = RelationshipContinuousAccumulator.CURRENT_VERSION.toLong(),
            accumulator_trust = accumulator.trust.toDouble(),
            accumulator_familiarity = accumulator.familiarity.toDouble(),
            accumulator_safety = accumulator.safety.toDouble(),
            accumulator_boundary_sensitivity = accumulator.boundarySensitivity.toDouble(),
            accumulator_unresolved_tension = accumulator.unresolvedTension.toDouble(),
            accumulator_reciprocal_interest = accumulator.reciprocalInterest.toDouble(),
            continuous_baseline_event_ids_json = json.encodeToString(state.continuousBaselineEventIds.sorted()),
        )
    }

    @Suppress("LongParameterList")
    private fun toState(
        incarnationId: String,
        canonicalSubjectId: String,
        trust: Double,
        familiarity: Double,
        safety: Double,
        boundarySensitivity: Double,
        unresolvedTension: Double,
        reciprocalInterest: Double,
        evidenceCount: Long,
        updatedAtMs: Long,
        phase: String,
        userConfessedAtMs: Long?,
        atriAcceptedAtMs: Long?,
        mutualCommitmentAtMs: Long?,
        preferredAddressesJson: String,
        continuousAccumulatorVersion: Long,
        accumulatorTrust: Double,
        accumulatorFamiliarity: Double,
        accumulatorSafety: Double,
        accumulatorBoundarySensitivity: Double,
        accumulatorUnresolvedTension: Double,
        accumulatorReciprocalInterest: Double,
        continuousBaselineEventIdsJson: String,
    ): RelationshipState = RelationshipState(
        incarnationId = incarnationId,
        canonicalSubjectId = canonicalSubjectId,
        trust = trust.toFloat(),
        familiarity = familiarity.toFloat(),
        safety = safety.toFloat(),
        boundarySensitivity = boundarySensitivity.toFloat(),
        unresolvedTension = unresolvedTension.toFloat(),
        reciprocalInterest = reciprocalInterest.toFloat(),
        evidenceCount = evidenceCount,
        updatedAtMs = updatedAtMs,
        facts = RelationshipFacts(
            phase = RelationshipPhase.valueOf(phase),
            userConfessedAtMs = userConfessedAtMs,
            atriAcceptedAtMs = atriAcceptedAtMs,
            mutualCommitmentAtMs = mutualCommitmentAtMs,
            preferredAddresses = json.decodeFromString<List<String>>(preferredAddressesJson).toSet(),
        ),
        continuousAccumulator = RelationshipContinuousAccumulator(
            trust = accumulatorTrust.toFloat(),
            familiarity = accumulatorFamiliarity.toFloat(),
            safety = accumulatorSafety.toFloat(),
            boundarySensitivity = accumulatorBoundarySensitivity.toFloat(),
            unresolvedTension = accumulatorUnresolvedTension.toFloat(),
            reciprocalInterest = accumulatorReciprocalInterest.toFloat(),
        ),
        continuousAccumulatorVersion = continuousAccumulatorVersion.toInt(),
        continuousBaselineEventIds = json.decodeFromString<List<String>>(continuousBaselineEventIdsJson).toSet(),
    )

    @Suppress("LongParameterList")
    private fun toEvent(
        eventId: String,
        incarnationId: String,
        canonicalSubjectId: String,
        sourceTurnId: String,
        eventType: String,
        confidence: Double,
        evidenceDigest: String,
        createdAtMs: Long,
        supersedesEventId: String?,
        preferredAddress: String?,
    ): RelationshipEvent = RelationshipEvent(
        eventId = eventId,
        incarnationId = incarnationId,
        canonicalSubjectId = canonicalSubjectId,
        sourceTurnId = sourceTurnId,
        type = RelationshipEventType.valueOf(eventType),
        confidence = confidence.toFloat(),
        evidenceDigest = evidenceDigest,
        createdAtMs = createdAtMs,
        supersedesEventId = supersedesEventId,
        preferredAddress = preferredAddress,
    )

    private fun JdbcSqliteDriver.closeCurrentThreadConnection() {
        closeConnection(getConnection())
    }

    companion object {
        private val json = Json

        suspend fun open(
            dbPath: Path,
            ioDispatcher: CoroutineDispatcher = newSqliteDispatcher("openeden-relationship-sqlite"),
        ): SqlDelightRelationshipStateStore = withContext(ioDispatcher) {
            try {
                dbPath.parent?.let(Files::createDirectories)
                val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
                SqlDelightRelationshipStateStore(Database(driver), driver, ioDispatcher)
            } catch (failure: Throwable) {
                (ioDispatcher as? ExecutorCoroutineDispatcher)?.close()
                throw failure
            }
        }
    }
}
