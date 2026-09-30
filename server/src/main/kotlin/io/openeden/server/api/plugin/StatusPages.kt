package io.openeden.server.api.plugin

import io.openeden.server.api.dto.InternalServerErrorDto
import io.openeden.server.api.dto.ChatGptAuthErrorDto
import io.openeden.server.auth.ChatGptAuthException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException
import java.util.UUID

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val traceId = "error_${UUID.randomUUID().toString().replace("-", "")}"
            if (cause is ChatGptAuthException) {
                call.application.log.warn("ChatGPT authentication unavailable code=${cause.code} traceId=$traceId")
                call.respond(HttpStatusCode.ServiceUnavailable, ChatGptAuthErrorDto(
                    cause.code, cause.message.orEmpty(), cause.reason.retryable, traceId,
                ))
                return@exception
            }
            call.application.log.error("Unhandled server error traceId=$traceId", cause)
            call.respond(HttpStatusCode.InternalServerError, InternalServerErrorDto(traceId = traceId))
        }
    }
}
