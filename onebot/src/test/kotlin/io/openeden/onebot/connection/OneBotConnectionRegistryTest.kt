package io.openeden.onebot.connection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneBotConnectionRegistryTest {
    @Test
    fun `replaces active connection and ignores stale disconnect`() = runTest {
        val registry = OneBotConnectionRegistry(expectedSelfId = "10001")
        val firstSocket = FakeSocket()
        val secondSocket = FakeSocket()

        val first = registry.register("10001", firstSocket)
        val second = registry.register("10001", secondSocket)

        assertTrue(second.epoch > first.epoch)
        assertEquals(1, firstSocket.closeCalls)
        assertEquals(second, registry.snapshot())

        assertFalse(registry.unregister(first.epoch))
        assertEquals(second, registry.snapshot())

        assertTrue(registry.unregister(second.epoch))
        assertNull(registry.snapshot())
    }

    @Test
    fun `rejects unexpected self id`() = runTest {
        val registry = OneBotConnectionRegistry(expectedSelfId = "10001")

        assertFailsWith<IllegalArgumentException> {
            registry.register("different", FakeSocket())
        }
    }

    private class FakeSocket : OneBotSocket {
        var closeCalls = 0

        override suspend fun send(text: String) = Unit

        override suspend fun close(reason: String) {
            closeCalls += 1
        }
    }
}
