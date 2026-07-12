package io.openeden.server

import io.openeden.bio.VectorDelta
import io.openeden.runtime.DevelopmentMessagePipeline
import io.openeden.runtime.DevelopmentMessageRequest
import io.ktor.server.request.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.*
import java.util.UUID
import kotlinx.serialization.Serializable

fun Application.configureRouting() {
    // Prefer the durable-backed pipeline published by configureRuntime; fall back to an in-memory
    // pipeline when routing is configured standalone (e.g. lightweight tests).
    val developmentPipeline = attributes.getOrNull(PipelineKey)
        ?: DevelopmentMessagePipeline.create(loadDefaultPersonaConfig())
    val sessionStateStore = attributes.getOrNull(SessionStateStoreKey)
    routing {
        get("/") {
            call.respondText("OpenEden runtime skeleton")
        }
        get("/health") {
            call.respond(HealthResponseDto(status = "ready", service = "openeden-server"))
        }
        get("/api/v1/state") {
            val userId = call.request.queryParameters["userId"]?.takeIf { it.isNotBlank() }
                ?: "local"
            val store = sessionStateStore ?: error("session state store is not configured")
            val state = store.readOrCreate("CLI:$userId")
            call.respond(
                PublicStateDto(
                    sessionId = state.sessionId,
                    status = "ready",
                    omega = state.omega.value,
                    shockActive = state.shockState?.active == true,
                ),
            )
        }
        post("/api/v1/chat") {
            val requestId = "req_${UUID.randomUUID().toString().replace("-", "")}"
            val request = call.receive<ChatRequestDto>()
            if (request.userId.isBlank() || request.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            try {
                val result = developmentPipeline.handle(
                    DevelopmentMessageRequest(
                        platform = "CLI",
                        scopeId = request.userId,
                        userId = request.userId,
                        text = request.text,
                        emotionConfidence = 0.0f,
                    ),
                )
                call.respond(
                    ChatResponseDto(
                        requestId = requestId,
                        status = "completed",
                        response = result.response,
                    ),
                )
            } catch (error: Throwable) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ChatResponseDto(
                        requestId = requestId,
                        status = "failed",
                        error = error.message ?: error::class.simpleName ?: "server error",
                    ),
                )
            }
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
