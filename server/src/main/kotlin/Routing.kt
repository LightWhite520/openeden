package io.openeden.server

import io.openeden.bio.VectorDelta
import io.openeden.persona.PersonaFileLoader
import io.openeden.runtime.DevelopmentMessagePipeline
import io.openeden.runtime.DevelopmentMessageRequest
import io.ktor.server.request.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

fun Application.configureRouting() {
    val developmentPipeline = DevelopmentMessagePipeline.create(loadDefaultPersonaConfig())
    routing {
        get("/") {
            call.respondText("OpenEden runtime skeleton")
        }
        post("/dev/message") {
            val request = call.receive<DevMessageRequestDto>()
            val result = developmentPipeline.handle(
                DevelopmentMessageRequest(
                    platform = request.platform,
                    scopeId = request.scopeId,
                    userId = request.userId,
                    text = request.text,
                    emotionConfidence = request.emotionConfidence,
                    emotionDelta = VectorDelta(
                        l = request.deltaL,
                        p = request.deltaP,
                        e = request.deltaE,
                        s = request.deltaS,
                        tau = request.deltaTau,
                        v = request.deltaV,
                        m = request.deltaM,
                        f = request.deltaF,
                    ),
                ),
            )
            call.respond(
                DevMessageResponseDto(
                    sessionId = result.sessionId,
                    retrievalMode = result.retrievalMode.name,
                    traceTags = result.traceTags.toList().sorted(),
                    promptPreview = result.promptPreview,
                    response = result.response,
                    updatedVector = result.updatedVector.toList(),
                    evolutionIndex = result.evolutionIndex,
                    diaryOutcome = result.diaryOutcome,
                    validationErrors = result.validationErrors,
                ),
            )
        }
        webSocket("/ws") { // websocketSession
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    outgoing.send(Frame.Text("YOU SAID: $text"))
                    if (text.equals("bye", ignoreCase = true)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                    }
                }
            }
        }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("service" to "openeden", "status" to "ready"))
        }
    }
}

@Serializable
data class DevMessageRequestDto(
    val platform: String,
    val scopeId: String,
    val userId: String,
    val text: String,
    val emotionConfidence: Float,
    val deltaL: Float = 0.0f,
    val deltaP: Float = 0.0f,
    val deltaE: Float = 0.0f,
    val deltaS: Float = 0.0f,
    val deltaTau: Float = 0.0f,
    val deltaV: Float = 0.0f,
    val deltaM: Float = 0.0f,
    val deltaF: Float = 0.0f,
)

@Serializable
data class DevMessageResponseDto(
    val sessionId: String,
    val retrievalMode: String,
    val traceTags: List<String>,
    val promptPreview: String,
    val response: String?,
    val updatedVector: List<Float>,
    val evolutionIndex: Long,
    val diaryOutcome: String,
    val validationErrors: List<String>,
)

private fun loadDefaultPersonaConfig() =
    PersonaFileLoader.load(resolveDefaultPersonaPath())

private fun resolveDefaultPersonaPath(): Path {
    var current = Path.of("").toAbsolutePath()
    repeat(6) {
        val candidate = current.resolve(Path.of("persona", "default.yaml"))
        if (Files.exists(candidate)) return candidate
        current.parent?.let { current = it } ?: return candidate
    }
    return Path.of("persona", "default.yaml")
}
