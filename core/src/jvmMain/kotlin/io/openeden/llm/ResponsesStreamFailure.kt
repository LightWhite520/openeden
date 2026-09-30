package io.openeden.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Fixed diagnostics only: provider messages and response bodies are intentionally excluded. */
class ResponsesStreamFailure(
    val terminalEvent: String,
    val providerCode: String,
    /** For explicit operator evidence capture only; never interpolated into logs or exceptions. */
    val providerEvent: JsonObject? = null,
) : IllegalStateException("Responses stream rejected: event=$terminalEvent code=$providerCode") {
    companion object {
        fun fromEvent(event: JsonObject): ResponsesStreamFailure {
            val response = event["response"] as? JsonObject
            val error = (response?.get("error") ?: event["error"]) as? JsonObject
            val incomplete = response?.get("incomplete_details") as? JsonObject
            val code = sequenceOf(error?.get("code"), event["code"], incomplete?.get("reason"), error?.get("type"))
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull()
            val safeCode = code?.takeIf { it in setOf(
                "rate_limit_exceeded", "insufficient_quota", "server_error", "invalid_request_error",
                "model_not_found", "invalid_api_key", "usage_limit_reached", "max_output_tokens",
                "content_filter", "context_length_exceeded", "unsupported_parameter",
                "subscription_sharing_usage_limit_exceeded",
            ) } ?: "OTHER"
            val type = (event["type"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it in setOf("response.failed", "response.incomplete", "error") } ?: "error"
            return ResponsesStreamFailure(type, safeCode, event)
        }
    }
}
