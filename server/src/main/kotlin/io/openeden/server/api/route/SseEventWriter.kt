package io.openeden.server.api.route

import io.openeden.server.api.dto.ChatStreamEventDto
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SseEventWriter(
    private val channel: ByteWriteChannel,
    private val json: Json = Json,
) {
    suspend fun send(eventName: String, event: ChatStreamEventDto) {
        channel.writeStringUtf8("event: $eventName\n")
        channel.writeStringUtf8("data: ${json.encodeToString(event)}\n\n")
        channel.flush()
    }
}
