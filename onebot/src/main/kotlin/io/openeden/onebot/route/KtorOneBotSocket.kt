package io.openeden.onebot.route

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.openeden.onebot.connection.OneBotSocket

class KtorOneBotSocket(
    private val session: DefaultWebSocketServerSession,
) : OneBotSocket {
    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close(reason: String) {
        session.close(
            CloseReason(
                CloseReason.Codes.NORMAL,
                reason.take(MAX_CLOSE_REASON_LENGTH),
            ),
        )
    }

    private companion object {
        const val MAX_CLOSE_REASON_LENGTH = 123
    }
}
