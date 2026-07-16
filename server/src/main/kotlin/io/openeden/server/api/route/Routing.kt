package io.openeden.server.api.route

import io.openeden.server.api.dto.ChatRequestDto
import io.openeden.server.api.dto.ChatResponseDto
import io.openeden.server.api.dto.ChatStreamEventDto
import io.openeden.server.api.dto.ChatStreamRequestDto
import io.openeden.server.api.dto.DiagnosticStateDto
import io.openeden.server.api.dto.DevMessageRequestDto
import io.openeden.server.api.dto.DevMessageResponseDto
import io.openeden.server.api.dto.HealthResponseDto
import io.openeden.server.api.dto.PublicStateDto
import io.openeden.server.bootstrap.PipelineKey
import io.openeden.server.bootstrap.SessionStateStoreKey
import io.openeden.server.bootstrap.loadDefaultPersonaConfig
import io.openeden.bio.VectorDelta
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.pipeline.DevelopmentMessageEvent
import io.openeden.runtime.pipeline.DevelopmentMessageRequest
import io.ktor.server.request.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import java.util.UUID

fun Application.configureRouting() {
    // Prefer the durable-backed pipeline published by configureRuntime; fall back to an in-memory
    // pipeline when routing is configured standalone (e.g. lightweight tests).
    val developmentPipeline = attributes.getOrNull(PipelineKey)
        ?: DevelopmentMessagePipeline.create(loadDefaultPersonaConfig())
    val sessionStateStore = attributes.getOrNull(SessionStateStoreKey)
    val diagnosticsAccess = attributes.getOrNull(DiagnosticsAccessKey) ?: DiagnosticsAccess.disabled()
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
        get("/api/v1/diagnostics") {
            if (!diagnosticsAccess.enabled) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val bearer = call.request.headers[HttpHeaders.Authorization]
                ?.removePrefix("Bearer ")
                ?.takeIf { it.isNotBlank() }
            if (!diagnosticsAccess.authorizes(bearer)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            val userId = call.request.queryParameters["userId"]?.takeIf { it.isNotBlank() }
                ?: "local"
            val store = sessionStateStore ?: error("session state store is not configured")
            val state = store.readOrCreate("CLI:$userId")
            call.respond(
                DiagnosticStateDto(
                    sessionId = state.sessionId,
                    vector = state.vector.toList(),
                    omega = state.omega.value,
                    shockActive = state.shockState?.active == true,
                    shockIntensity = state.shockState?.intensity,
                    evolutionIndex = state.evolutionIndex,
                    derivedDissonance = state.vector.derivedDissonance(),
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
        post("/api/v1/chat/stream") {
            val requestId = "req_${UUID.randomUUID().toString().replace("-", "")}"
            val request = call.receive<ChatStreamRequestDto>()
            if (request.userId.isBlank() || request.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                val events = SseEventWriter(this)
                events.send("accepted", ChatStreamEventDto.Accepted(requestId))
                try {
                    developmentPipeline.handleStreaming(
                        DevelopmentMessageRequest(
                            platform = "CLI",
                            scopeId = request.userId,
                            userId = request.userId,
                            text = request.text,
                            emotionConfidence = 0.0f,
                        ),
                    ).collect { event ->
                        when (event) {
                            is DevelopmentMessageEvent.Stage -> events.send(
                                "stage",
                                ChatStreamEventDto.Stage(event.value.name.lowercase()),
                            )
                            is DevelopmentMessageEvent.ResponseDelta -> events.send(
                                "response.delta",
                                ChatStreamEventDto.ResponseDelta(event.text),
                            )
                            is DevelopmentMessageEvent.Completed -> {
                                if (event.result.validationErrors.isEmpty()) {
                                    events.send(
                                        "completed",
                                        ChatStreamEventDto.Completed(requestId, "completed"),
                                    )
                                } else {
                                    events.send(
                                        "error",
                                        ChatStreamEventDto.Error(
                                            code = "TURN_REJECTED",
                                            message = "response validation failed",
                                            retryable = true,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    events.send(
                        "error",
                        ChatStreamEventDto.Error(
                            code = "TURN_FAILED",
                            message = "request failed",
                            retryable = true,
                        ),
                    )
                }
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
