package io.openeden.server.persistence.sqldelight

import io.openeden.relationship.RelationshipCorrection
import io.openeden.relationship.RelationshipEvent
import io.openeden.relationship.RelationshipEventType
import io.openeden.relationship.RelationshipPhase
import io.openeden.relationship.RelationshipState
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
            assertEquals(0.4f, host.reciprocalInterest)
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
    fun `legacy relationship rows migrate to neutral incarnation subject state`() = runTest {
        val dbPath = Files.createTempFile("openeden-relationship-legacy", ".db")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
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
}
