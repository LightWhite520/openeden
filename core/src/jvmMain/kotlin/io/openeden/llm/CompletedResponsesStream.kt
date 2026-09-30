package io.openeden.llm

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.serialization.json.*

/** Collect a bounded structured evaluator response, requiring a successful terminal SSE event. */
suspend fun completedResponsesStream(channel: ByteReadChannel, limit: Int = 64 * 1024): String {
    val data = StringBuilder()
    var total = 0
    var completed: JsonObject? = null
    val textParts = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy({ it.first }, { it.second }))
    val finishedParts = mutableSetOf<Pair<Int, Int>>()
    fun frame() {
        if (data.isEmpty()) return
        val payload = data.toString()
        data.clear()
        if (payload == "[DONE]") return
        val event = Json.parseToJsonElement(payload).jsonObject
        when (event["type"]?.jsonPrimitive?.content) {
            "response.output_text.delta", "response.output_text.done" -> {
                check(completed == null) { "Text received after response completion" }
                val key = (event["output_index"]?.jsonPrimitive?.int ?: 0) to
                    (event["content_index"]?.jsonPrimitive?.int ?: 0)
                val part = textParts.getOrPut(key) { StringBuilder() }
                check(key !in finishedParts) { "Text received after part completion" }
                if (event.getValue("type").jsonPrimitive.content == "response.output_text.done") {
                    part.clear()
                    part.append(event.getValue("text").jsonPrimitive.content)
                    finishedParts += key
                } else {
                    part.append(event.getValue("delta").jsonPrimitive.content)
                }
            }
            "response.completed" -> {
                check(completed == null) { "Duplicate response completion" }
                completed = event.getValue("response").jsonObject
            }
            "response.failed", "response.incomplete", "error" -> {
                throw ResponsesStreamFailure.fromEvent(event)
            }
        }
    }
    while (!channel.isClosedForRead) {
        val line = channel.readLine() ?: break
        total += line.length
        check(total <= limit) { "ChatGPT subscription response exceeded limit" }
        if (line.isEmpty()) frame()
        else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.removePrefix("data:").trimStart())
        }
    }
    frame()
    val response = checkNotNull(completed) { "ChatGPT subscription stream ended before completion" }
    val hasText = response["output_text"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true ||
        response["output"]?.jsonArray.orEmpty().any { item ->
            item.jsonObject["content"]?.jsonArray.orEmpty().any { content ->
                content.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text"
            }
        }
    return if (!hasText && textParts.isNotEmpty()) {
        JsonObject(response + ("output_text" to JsonPrimitive(textParts.values.joinToString("")))).toString()
    } else response.toString()
}
