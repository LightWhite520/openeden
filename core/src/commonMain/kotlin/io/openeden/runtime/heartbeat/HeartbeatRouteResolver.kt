package io.openeden.runtime.heartbeat

fun interface HeartbeatRouteResolver {
    fun targetsFor(sessionId: String, nowMs: Long): List<HeartbeatTarget>
}

class OwnerHeartbeatRouteResolver(
    private val owner: HeartbeatOwner?,
) : HeartbeatRouteResolver {
    override fun targetsFor(sessionId: String, nowMs: Long): List<HeartbeatTarget> =
        owner?.let { listOf(HeartbeatTarget(it.platform, it.userId)) }.orEmpty()
}
