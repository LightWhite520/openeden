package io.openeden.onebot.connection

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OneBotConnectionRegistry(private val expectedSelfId: String) {
    private val epochs = AtomicLong()
    private val mutation = Mutex()

    @Volatile
    private var active: OneBotConnection? = null

    fun snapshot(): OneBotConnection? = active

    fun isActive(epoch: Long): Boolean = active?.epoch == epoch

    suspend fun register(selfId: String, socket: OneBotSocket): OneBotConnection {
        require(selfId == expectedSelfId) { "Unexpected OneBot self ID" }
        var replaced: OneBotConnection? = null
        val connection = mutation.withLock {
            replaced = active
            OneBotConnection(selfId, epochs.incrementAndGet(), socket).also { active = it }
        }
        replaced?.let { runCatching { it.socket.close("replaced") } }
        return connection
    }

    suspend fun unregister(epoch: Long): Boolean = mutation.withLock {
        if (active?.epoch != epoch) return@withLock false
        active = null
        true
    }

    suspend fun close() {
        val connection = mutation.withLock {
            active.also { active = null }
        }
        connection?.socket?.close("server shutdown")
    }
}
