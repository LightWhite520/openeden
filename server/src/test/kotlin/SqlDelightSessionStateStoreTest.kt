package io.openeden.server

import io.openeden.bio.BioVector
import io.openeden.runtime.OmegaState
import io.openeden.runtime.SessionState
import io.openeden.runtime.ShockState
import io.openeden.server.db.SqlDelightSessionStateStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightSessionStateStoreTest {
    private val tempDir = Files.createTempDirectory("openeden-db-test")
    private val dbPath = tempDir.resolve("openeden.db")

    @AfterTest
    fun cleanup() {
        Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(tempDir)
    }

    private val sample = SessionState(
        sessionId = "QQ:42",
        vector = BioVector(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f),
        origin = BioVector.Neutral,
        omega = OmegaState(0.33f),
        shockState = ShockState(
            active = true,
            intensity = 0.71f,
            description = "x",
            triggeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            decayLambda = 0.001f,
            shockHeartbeatFired = true,
        ),
        evolutionIndex = 17,
        lastUserActivityMs = 1_700_000_123_456L,
    )

    @Test
    fun `write then read round-trips full session state`() = runTest {
        val store = SqlDelightSessionStateStore.open(dbPath)
        try {
            store.write(sample)
            assertEquals(sample, store.read("QQ:42"))
            assertTrue("QQ:42" in store.sessionIds())
        } finally {
            store.close()
        }
    }

    @Test
    fun `unknown session reads as neutral, not null`() = runTest {
        val store = SqlDelightSessionStateStore.open(dbPath)
        try {
            val state = store.readOrCreate("QQ:missing")
            assertEquals(0, state.evolutionIndex)
            assertNull(state.shockState)
            assertNull(state.lastUserActivityMs)
        } finally {
            store.close()
        }
    }

    @Test
    fun `state survives a simulated restart (new store on same file)`() = runTest {
        SqlDelightSessionStateStore.open(dbPath).use { store ->
            store.write(sample.copy(evolutionIndex = 99))
        }
        // A fresh store on the same file == process restart. Growth must not reset (§1.1).
        SqlDelightSessionStateStore.open(dbPath).use { reopened ->
            assertEquals(99, reopened.read("QQ:42").evolutionIndex)
        }
    }

    private inline fun SqlDelightSessionStateStore.use(block: (SqlDelightSessionStateStore) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
