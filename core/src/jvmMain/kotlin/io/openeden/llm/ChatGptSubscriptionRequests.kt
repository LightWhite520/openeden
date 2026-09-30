package io.openeden.llm

import kotlinx.serialization.json.*

/** Public SIWC Responses HTTP contract, independent from persona and runtime state. */
object ChatGptSubscriptionRequests {
    const val BASE_URL = "https://api.openai.com/v1"
    // ChatGPT routes cache affinity by this header, not the JSON prompt_cache_key.
    // See openai/codex: codex-rs/core/src/client.rs, responses_session_id.
    const val SESSION_ID_HEADER = "session-id"

    fun adapt(body: JsonObject): JsonObject = JsonObject(buildMap {
        putAll(body.filterKeys { it !in unsupported })
        put("store", JsonPrimitive(false))
        put("stream", JsonPrimitive(true))
        put("input", JsonArray(body.getValue("input").jsonArray.map { item ->
            val message = item.jsonObject
            if (message["role"]?.jsonPrimitive?.content == "system") {
                JsonObject(message + ("role" to JsonPrimitive("developer")))
            } else message
        }))
    })

    private val unsupported = setOf(
        "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation",
        "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature",
        "top_logprobs", "top_p", "truncation", "user", "previous_response_id", "prompt_cache_options",
    )
}
