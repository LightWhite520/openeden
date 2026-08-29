package io.openeden.server.persistence.sqldelight

import io.openeden.relationship.RelationshipCorrection
import io.openeden.relationship.RelationshipEvent
import io.openeden.relationship.RelationshipEventType
import io.openeden.relationship.RelationshipPhase
import io.openeden.relationship.RelationshipState
import io.openeden.server.db.Database
import io.openeden.server.persistence.sqldelight.SqlDelightRelationshipStateStore
import kotlinx.coroutines.test.runTest
import java.sql.DriverManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlDelightRelationshipStateStoreTest {
    @Test
    fun `first append reduces and survives reopen without a prior snapshot`() = runTest {
        val dbPath = Files.createTempFile("openeden-relationship-first-append", ".db")
        val store = SqlDelightRelationshipStateStore.open(dbPath)
        val appended = try {
            store.append(userConfession("first-confession"))
        } finally {
            store.close()
        }

        assertNotNull(appended.facts.userConfessedAtMs)
        assertEquals(10L, appended.facts.userConfessedAtMs)

        val reopened = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val persisted = reopened.readOrCreate("inc-1", "host")
            assertEquals(10L, persisted.facts.userConfessedAtMs)
            assertEquals(RelationshipPhase.FAMILIAR, persisted.facts.phase)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `relationship state survives restart and remains isolated by incarnation and subject`() = runTest {
        val directory = Files.createTempDirectory("openeden-relationship")
        val dbPath = directory.resolve("runtime.db")
        val store = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val initial = store.readOrCreate("inc-1", "host", 10L)
            store.write(initial.copy(reciprocalInterest = 0.4f, updatedAtMs = 20L))
            store.append(userConfession("confession"))
            store.append(atriAcceptance("acceptance"))
            store.write(RelationshipState.neutral("inc-1", "other", 30L))
        } finally {
            store.close()
        }

        val reopened = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val host = reopened.readOrCreate("inc-1", "host")
            val other = reopened.readOrCreate("inc-1", "other")
            assertEquals(RelationshipPhase.COUPLE, host.facts.phase)
            assertNotNull(host.facts.mutualCommitmentAtMs)
            assertEquals(0.5f, host.reciprocalInterest)
            assertEquals(0L, other.evidenceCount)
            assertEquals("other", other.canonicalSubjectId)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `event replay is idempotent and corrections and reset retain audit history`() = runTest {
        val dbPath = Files.createTempFile("openeden-relationship-reset", ".db")
        val store = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val confession = userConfession("confession")
            store.append(confession)
            store.append(confession)
            assertEquals(1, store.events("inc-1", "host").size)

            val correction = RelationshipCorrection(
                event = event(
                    sourceTurnId = "correction",
                    type = RelationshipEventType.CONFLICT,
                    createdAtMs = 20L,
                    supersedesEventId = confession.eventId,
                ),
            )
            store.correct(correction)
            assertNull(store.readOrCreate("inc-1", "host").facts.userConfessedAtMs)

            store.reset(
                incarnationId = "inc-1",
                canonicalSubjectId = "host",
                sourceTurnId = "reset-turn",
                eventId = "reset-event",
                createdAtMs = 30L,
            )

            val reset = store.readOrCreate("inc-1", "host")
            assertEquals(RelationshipPhase.STRANGER, reset.facts.phase)
            assertEquals(3, store.events("inc-1", "host").size)
        } finally {
            store.close()
        }
    }

    @Test
    fun `restart then correction restores exact trust after a clamped conflict`() = runTest {
        val dbPath = Files.createTempFile("openeden-relationship-boundary", ".db")
        val conflict = event("conflict", RelationshipEventType.CONFLICT, 10L)
        val store = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            store.write(RelationshipState.neutral("inc-1", "host").copy(trust = 0.02f))
            assertEquals(0.0f, store.append(conflict).trust, 0.00001f)
        } finally {
            store.close()
        }

        val reopened = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val corrected = reopened.correct(
                RelationshipCorrection(
                    event = event(
                        sourceTurnId = "correction",
                        type = RelationshipEventType.PREFERENCE_RESPECTED,
                        createdAtMs = 20L,
                        supersedesEventId = conflict.eventId,
                    ),
                ),
            )

            assertEquals(0.02f, corrected.trust, 0.00001f)
            assertEquals(0.52f, corrected.safety, 0.00001f)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `version 17 relationship state migrates deterministically through current schema`() = runTest {
        val dbPath = Files.createTempFile("openeden-relationship-v17", ".db")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(VERSION_17_RELATIONSHIP_STATE_SQL)
                statement.executeUpdate(VERSION_17_RELATIONSHIP_EVENTS_SQL)
                statement.executeUpdate(LEGACY_PROMPT_HISTORY_STATE_SQL)
                statement.executeUpdate(LEGACY_PROMPT_HISTORY_CHUNKS_SQL)
                statement.executeUpdate(LEGACY_PROMPT_HISTORY_CHUNKS_INDEX_SQL)
                statement.executeUpdate(PRE_V21_SESSION_STATE_SQL)
                statement.executeUpdate(PRE_V21_DIARY_CHECKPOINTS_SQL)
                statement.executeUpdate(VERSION_19_INCARNATION_STATE_SQL)
                statement.executeUpdate(
                    """
                    INSERT INTO relationship_state VALUES (
                        'inc-1', 'host', 0.0, 0.0, 0.5, 0.0, 0.12, 0.0, 1, 10,
                        'STRANGER', NULL, NULL, NULL, '[]'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO relationship_events VALUES (
                        'conflict:CONFLICT', 'inc-1', 'host', 'conflict', 'CONFLICT',
                        1.0, 'CONFLICT', 10, NULL, NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version = 17")
            }
        }

        val migrated = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val state = migrated.readOrCreate("inc-1", "host")
            assertEquals(0.0f, state.trust, 0.00001f)
            assertEquals(0.12f, state.unresolvedTension, 0.00001f)
            assertEquals(setOf("conflict:CONFLICT"), state.continuousBaselineEventIds)

            val corrected = migrated.correct(
                RelationshipCorrection(
                    event = event(
                        sourceTurnId = "legacy-correction",
                        type = RelationshipEventType.PREFERENCE_RESPECTED,
                        createdAtMs = 20L,
                        supersedesEventId = "conflict:CONFLICT",
                    ),
                ),
            )
            assertEquals(0.0f, corrected.trust, 0.00001f)
            assertEquals(0.5f, corrected.safety, 0.00001f)
            assertEquals(0.12f, corrected.unresolvedTension, 0.00001f)
            assertEquals(1L, corrected.evidenceCount)
            assertEquals(2, corrected.events.size)
        } finally {
            migrated.close()
        }

        val reopened = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val state = reopened.readOrCreate("inc-1", "host")
            assertEquals(setOf("conflict:CONFLICT"), state.continuousBaselineEventIds)
            assertEquals(0.0f, state.trust, 0.00001f)
            assertEquals(0.5f, state.safety, 0.00001f)
            assertEquals(0.12f, state.unresolvedTension, 0.00001f)
            assertEquals(1L, state.evidenceCount)
            assertEquals(2, state.events.size)
        } finally {
            reopened.close()
        }

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    assertEquals(Database.Schema.version, result.getLong(1))
                }
            }
        }
    }

    @Test
    fun `legacy relationship rows migrate to neutral incarnation subject state`() = runTest {
        val dbPath = Files.createTempFile("openeden-relationship-legacy", ".db")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE diary_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        source_memory_id TEXT,
                        reason TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        available_at_ms INTEGER NOT NULL,
                        lease_expires_at_ms INTEGER,
                        lease_token TEXT,
                        last_error TEXT,
                        incarnation_id TEXT NOT NULL DEFAULT 'legacy-incarnation',
                        source_session_id TEXT NOT NULL DEFAULT '',
                        canonical_subject_id TEXT NOT NULL DEFAULT '',
                        visibility_kind TEXT NOT NULL DEFAULT 'SCOPE_SHARED',
                        visibility_subject_id TEXT,
                        visibility_session_id TEXT
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE relationship_state (
                        session_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        trust REAL NOT NULL,
                        familiarity REAL NOT NULL,
                        safety REAL NOT NULL,
                        boundary_sensitivity REAL NOT NULL,
                        unresolved_tension REAL NOT NULL,
                        evidence_count INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(session_id, user_id)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO relationship_state VALUES ('QQ:group', 'host', 0.9, 0.9, 0.9, 0.0, 0.0, 8, 99)
                    """.trimIndent(),
                )
                statement.executeUpdate(LEGACY_PROMPT_HISTORY_STATE_SQL)
                statement.executeUpdate(LEGACY_PROMPT_HISTORY_CHUNKS_SQL)
                statement.executeUpdate(LEGACY_PROMPT_HISTORY_CHUNKS_INDEX_SQL)
                statement.executeUpdate(PRE_V21_SESSION_STATE_SQL)
                statement.executeUpdate(PRE_V21_DIARY_CHECKPOINTS_SQL)
                statement.executeUpdate(VERSION_19_INCARNATION_STATE_SQL)
                statement.execute("PRAGMA user_version = 14")
            }
        }

        val store = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val migrated = store.readOrCreate("legacy-incarnation", "QQ:host")
            assertEquals(RelationshipPhase.STRANGER, migrated.facts.phase)
            assertEquals(0L, migrated.evidenceCount)
            assertEquals(0.0f, migrated.reciprocalInterest)
        } finally {
            store.close()
        }
    }

    private fun userConfession(sourceTurnId: String): RelationshipEvent =
        event(sourceTurnId, RelationshipEventType.USER_CONFESSION, 10L)

    private fun atriAcceptance(sourceTurnId: String): RelationshipEvent =
        event(sourceTurnId, RelationshipEventType.ATRI_ACCEPTANCE, 20L)

    private fun event(
        sourceTurnId: String,
        type: RelationshipEventType,
        createdAtMs: Long,
        supersedesEventId: String? = null,
    ): RelationshipEvent = RelationshipEvent(
        eventId = "$sourceTurnId:${type.name}",
        incarnationId = "inc-1",
        canonicalSubjectId = "host",
        sourceTurnId = sourceTurnId,
        type = type,
        confidence = 1.0f,
        evidenceDigest = type.name,
        createdAtMs = createdAtMs,
        supersedesEventId = supersedesEventId,
    )

    private companion object {
        val VERSION_19_INCARNATION_STATE_SQL =
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
            """.trimIndent()

        val VERSION_17_RELATIONSHIP_STATE_SQL =
            """
            CREATE TABLE relationship_state (
                incarnation_id TEXT NOT NULL,
                canonical_subject_id TEXT NOT NULL,
                trust REAL NOT NULL,
                familiarity REAL NOT NULL,
                safety REAL NOT NULL,
                boundary_sensitivity REAL NOT NULL,
                unresolved_tension REAL NOT NULL,
                reciprocal_interest REAL NOT NULL,
                evidence_count INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                phase TEXT NOT NULL,
                user_confessed_at_ms INTEGER,
                atri_accepted_at_ms INTEGER,
                mutual_commitment_at_ms INTEGER,
                preferred_addresses_json TEXT NOT NULL,
                PRIMARY KEY(incarnation_id, canonical_subject_id)
            )
            """.trimIndent()

        val VERSION_17_RELATIONSHIP_EVENTS_SQL =
            """
            CREATE TABLE relationship_events (
                event_id TEXT NOT NULL PRIMARY KEY,
                incarnation_id TEXT NOT NULL,
                canonical_subject_id TEXT NOT NULL,
                source_turn_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                confidence REAL NOT NULL,
                evidence_digest TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                supersedes_event_id TEXT,
                preferred_address TEXT,
                UNIQUE(source_turn_id, event_type, incarnation_id, canonical_subject_id)
            )
            """.trimIndent()

        val LEGACY_PROMPT_HISTORY_CHUNKS_SQL =
            """
            CREATE TABLE prompt_history_chunks (
                chunk_id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                cache_epoch INTEGER NOT NULL,
                first_turn_id TEXT NOT NULL,
                last_turn_id TEXT NOT NULL,
                turn_ids_json TEXT NOT NULL,
                serialized_text TEXT NOT NULL,
                token_count INTEGER NOT NULL,
                fingerprint TEXT NOT NULL,
                serializer_version INTEGER NOT NULL
            )
            """.trimIndent()

        val LEGACY_PROMPT_HISTORY_STATE_SQL =
            """
            CREATE TABLE prompt_history_state (
                session_id TEXT NOT NULL PRIMARY KEY,
                cache_epoch INTEGER NOT NULL,
                serializer_version INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()

        val LEGACY_PROMPT_HISTORY_CHUNKS_INDEX_SQL =
            """
            CREATE INDEX prompt_history_chunks_session_epoch
            ON prompt_history_chunks(session_id, cache_epoch, first_turn_id, last_turn_id, chunk_id)
            """.trimIndent()
    }
}
