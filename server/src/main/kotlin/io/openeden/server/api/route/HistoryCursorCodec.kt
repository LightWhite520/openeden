package io.openeden.server.api.route

import io.openeden.transcript.HistoryCursor
import io.openeden.transcript.InvalidHistoryCursorException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64

object HistoryCursorCodec {
    private val json = Json
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(cursor: HistoryCursor): String {
        val payload = CursorPayload(
            incarnationId = cursor.incarnationId,
            completedAtMs = cursor.completedAtMs,
            turnId = cursor.turnId,
        )
        return encoder.encodeToString(
            json.encodeToString(CursorPayload.serializer(), payload)
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun decode(value: String): HistoryCursor = try {
        val payload = json.decodeFromString(
            CursorPayload.serializer(),
            String(decoder.decode(value), StandardCharsets.UTF_8),
        )
        require(payload.incarnationId.isNotBlank() && payload.turnId.isNotBlank())
        HistoryCursor(
            incarnationId = payload.incarnationId,
            completedAtMs = payload.completedAtMs,
            turnId = payload.turnId,
        )
    } catch (failure: Exception) {
        throw InvalidHistoryCursorException("History cursor could not be decoded").also {
            it.initCause(failure)
        }
    }

    @Serializable
    private data class CursorPayload(
        val incarnationId: String,
        val completedAtMs: Long,
        val turnId: String,
    )
}
