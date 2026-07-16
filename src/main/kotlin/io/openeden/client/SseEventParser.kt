package io.openeden.client

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

class SseEventParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(chunks: Flow<ByteArray>): Flow<ChatStreamEvent> = flow {
        val pending = ByteArrayOutputStream()
        chunks.collect { chunk ->
            pending.write(chunk)
            val bytes = pending.toByteArray()
            var frameStart = 0
            while (true) {
                val boundary = findFrameBoundary(bytes, frameStart) ?: break
                decodeFrame(bytes.copyOfRange(frameStart, boundary.frameEnd).decodeToString())?.let { emit(it) }
                frameStart = boundary.nextFrameStart
            }
            if (frameStart > 0) {
                pending.reset()
                pending.write(bytes, frameStart, bytes.size - frameStart)
            }
        }
        val trailing = pending.toByteArray().decodeToString()
        if (trailing.isNotBlank()) decodeFrame(trailing)?.let { emit(it) }
    }

    fun parse(channel: ByteReadChannel): Flow<ChatStreamEvent> = parse(
        flow {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (count > 0) emit(buffer.copyOf(count))
            }
        },
    )

    private fun findFrameBoundary(bytes: ByteArray, start: Int): FrameBoundary? {
        var index = start
        while (index < bytes.size - 1) {
            if (bytes[index] == LF && bytes[index + 1] == LF) {
                return FrameBoundary(index, index + 2)
            }
            if (
                index < bytes.size - 3 &&
                bytes[index] == CR && bytes[index + 1] == LF &&
                bytes[index + 2] == CR && bytes[index + 3] == LF
            ) {
                return FrameBoundary(index, index + 4)
            }
            index += 1
        }
        return null
    }

    private fun decodeFrame(frame: String): ChatStreamEvent? {
        var eventName: String? = null
        val data = StringBuilder()
        frame.lineSequence().forEach { line ->
            when {
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        val payload = data.toString()
        return when (eventName) {
            "accepted" -> json.decodeFromString<AcceptedPayload>(payload).let {
                ChatStreamEvent.Accepted(it.requestId)
            }
            "stage" -> json.decodeFromString<StagePayload>(payload).let {
                ChatStreamEvent.Stage(it.stage)
            }
            "response.delta" -> json.decodeFromString<ResponseDeltaPayload>(payload).let {
                ChatStreamEvent.ResponseDelta(it.text)
            }
            "completed" -> json.decodeFromString<CompletedPayload>(payload).let {
                ChatStreamEvent.Completed(it.requestId, it.status)
            }
            "error" -> json.decodeFromString<ErrorPayload>(payload).let {
                ChatStreamEvent.Error(it.code, it.message, it.retryable)
            }
            else -> null
        }
    }

    private data class FrameBoundary(val frameEnd: Int, val nextFrameStart: Int)

    private companion object {
        const val CR: Byte = 13
        const val LF: Byte = 10
    }
}

@Serializable
private data class AcceptedPayload(val requestId: String)

@Serializable
private data class StagePayload(val stage: String)

@Serializable
private data class ResponseDeltaPayload(val text: String)

@Serializable
private data class CompletedPayload(val requestId: String, val status: String)

@Serializable
private data class ErrorPayload(val code: String, val message: String, val retryable: Boolean)
