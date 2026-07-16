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

    fun encode(cursor: HistoryCursor): String = try {
        val payload = CursorPayload(
            incarnationId = cursor.incarnationId,
            completedAtMs = cursor.completedAtMs,
            turnId = cursor.turnId,
        )
        validate(payload)
        val decoded = serialize(payload)
        require(decoded.size <= MAX_DECODED_BYTES)
        encoder.encodeToString(decoded).also { encoded ->
            require(encoded.length <= MAX_ENCODED_LENGTH)
        }
    } catch (failure: Exception) {
        throw invalidCursor(failure)
    }

    fun decode(value: String): HistoryCursor = try {
        require(
            value.isNotEmpty() &&
                value.length <= MAX_ENCODED_LENGTH &&
                value.indexOf('=') < 0 &&
                value.all(::isBase64UrlCharacter),
        )
        val decoded = decoder.decode(value)
        require(decoded.size <= MAX_DECODED_BYTES)
        require(encoder.encodeToString(decoded) == value)
        val payload = json.decodeFromString(
            CursorPayload.serializer(),
            String(decoded, StandardCharsets.UTF_8),
        )
        validate(payload)
        require(serialize(payload).contentEquals(decoded))
        HistoryCursor(
            incarnationId = payload.incarnationId,
            completedAtMs = payload.completedAtMs,
            turnId = payload.turnId,
        )
    } catch (failure: Exception) {
        throw invalidCursor(failure)
    }

    private fun serialize(payload: CursorPayload): ByteArray =
        json.encodeToString(CursorPayload.serializer(), payload).toByteArray(StandardCharsets.UTF_8)

    private fun validate(payload: CursorPayload) {
        require(payload.incarnationId.isSafeIdentifier())
        require(payload.turnId.isSafeIdentifier())
        require(payload.completedAtMs >= 0L)
    }

    private fun String.isSafeIdentifier(): Boolean =
        length in 1..MAX_IDENTIFIER_LENGTH && all(::isIdentifierCharacter)

    private fun isIdentifierCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'

    private fun invalidCursor(cause: Exception) =
        InvalidHistoryCursorException("History cursor is invalid").also { it.initCause(cause) }

    private fun isBase64UrlCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'

    @Serializable
    private data class CursorPayload(
        val incarnationId: String,
        val completedAtMs: Long,
        val turnId: String,
    )

    private const val MAX_ENCODED_LENGTH = 1024
    private const val MAX_DECODED_BYTES = 512
    private const val MAX_IDENTIFIER_LENGTH = 128
}
