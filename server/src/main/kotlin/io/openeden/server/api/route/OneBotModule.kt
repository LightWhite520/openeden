package io.openeden.server.api.route

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.openeden.onebot.route.oneBotReverseWebSocket
import io.openeden.server.bootstrap.OneBotAdapterKey

fun Application.configureOneBot() {
    attributes.getOrNull(OneBotAdapterKey)?.let { adapter ->
        routing {
            oneBotReverseWebSocket(adapter)
        }
    }
}
