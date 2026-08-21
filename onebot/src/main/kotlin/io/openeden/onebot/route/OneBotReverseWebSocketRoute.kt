package io.openeden.onebot.route

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.openeden.onebot.ingress.OneBotAdapter

fun Route.oneBotReverseWebSocket(adapter: OneBotAdapter) {
    if (!adapter.config.enabled) return

    route(adapter.config.path) {
        webSocket {
            val selfId = call.request.headers[SELF_ID_HEADER]
            if (!authorized(call.request.headers[HttpHeaders.Authorization], adapter.config.accessToken) ||
                selfId != adapter.config.botSelfId
            ) {
                adapter.trace("onebot=AUTH_REJECTED")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, AUTHENTICATION_FAILURE_REASON))
                return@webSocket
            }

            val connection = adapter.registry.register(selfId, KtorOneBotSocket(this))
            adapter.trace("onebot=CONNECTED epoch=${connection.epoch}")
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> adapter.onText(frame.readText(), connection.epoch)
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            } finally {
                adapter.actions.failEpoch(connection.epoch, CONNECTION_CLOSED)
                adapter.registry.unregister(connection.epoch)
                adapter.trace("onebot=DISCONNECTED epoch=${connection.epoch}")
            }
        }
    }
}

private fun authorized(header: String?, expectedToken: String): Boolean {
    val suppliedToken = header?.takeIf { it.startsWith(BEARER_PREFIX) }
        ?.removePrefix(BEARER_PREFIX)
        ?: return false
    return MessageDigest.isEqual(
        suppliedToken.toByteArray(StandardCharsets.UTF_8),
        expectedToken.toByteArray(StandardCharsets.UTF_8),
    )
}

private const val SELF_ID_HEADER = "X-Self-ID"
private const val BEARER_PREFIX = "Bearer "
private const val AUTHENTICATION_FAILURE_REASON = "OneBot authentication failed"
private val CONNECTION_CLOSED = IllegalStateException("OneBot connection closed")
