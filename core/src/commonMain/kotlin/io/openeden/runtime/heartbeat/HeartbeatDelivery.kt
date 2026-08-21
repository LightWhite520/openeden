package io.openeden.runtime.heartbeat

/**
 * Immediate heartbeat delivery boundary.
 *
 * [isConnected] must return only a non-blocking, thread-safe connection snapshot. Connection state
 * may race after that snapshot, so [deliver] must drop or throw when unavailable and must never queue
 * or replay a heartbeat.
 */
interface HeartbeatDelivery {
    fun isConnected(target: HeartbeatTarget): Boolean

    suspend fun deliver(sessionId: String, target: HeartbeatTarget, shock: Boolean, response: String?)
}
