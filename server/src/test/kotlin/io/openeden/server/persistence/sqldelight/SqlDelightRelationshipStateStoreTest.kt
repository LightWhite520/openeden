package io.openeden.server.persistence.sqldelight

import io.openeden.relationship.RelationshipEvidence
import io.openeden.relationship.RelationshipState
import io.openeden.server.persistence.sqldelight.SqlDelightRelationshipStateStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightRelationshipStateStoreTest {
    @Test
    fun `relationship state survives restart and remains per user`() = runTest {
        val directory = Files.createTempDirectory("openeden-relationship")
        val dbPath = directory.resolve("runtime.db")
        val store = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val initial = store.readOrCreate("QQ:group", "u1", 10L)
            store.write(initial.apply(RelationshipEvidence.REPAIR, 20L))
            store.write(RelationshipState.neutral("QQ:group", "u2", 30L))
        } finally {
            store.close()
        }

        val reopened = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            val u1 = reopened.readOrCreate("QQ:group", "u1")
            val u2 = reopened.readOrCreate("QQ:group", "u2")
            assertEquals(1L, u1.evidenceCount)
            assertEquals(20L, u1.updatedAtMs)
            assertEquals(0L, u2.evidenceCount)
            assertEquals("u2", u2.userId)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `reset removes only selected relationship`() = runTest {
        val dbPath = Files.createTempFile("openeden-relationship-reset", ".db")
        val store = SqlDelightRelationshipStateStore.open(dbPath)
        try {
            store.write(RelationshipState.neutral("CLI:u", "u", 1L))
            store.write(RelationshipState.neutral("CLI:u", "other", 2L))
            store.reset("CLI:u", "u")

            assertEquals(0L, store.readOrCreate("CLI:u", "u").evidenceCount)
            assertEquals("other", store.readOrCreate("CLI:u", "other").userId)
        } finally {
            store.close()
        }
    }
}
