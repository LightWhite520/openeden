package io.openeden.server.evaluation.compaction

import io.openeden.server.persistence.sqldelight.SqlDelightTranscriptStore
import io.openeden.transcript.PromptHistoryCompactor
import io.openeden.transcript.PromptHistorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Explicit isolated-database probe; never scheduled by the message pipeline. */
suspend fun main(args: Array<String>) {
    require(args.size in 2..3 && args[0] in setOf("prepare", "apply")) {
        "Usage: prepare|apply <isolated probe directory under build> [request-id]"
    }
    val directory = withContext(Dispatchers.IO) {
        Path.of(args[1]).toRealPath().also {
            require(it.startsWith(Path.of("build").toRealPath()))
            require(Files.isRegularFile(it.resolve("p2-probe.json")))
            require(it.resolve("runtime.db").toRealPath().parent == it)
        }
    }
    val json = Json { prettyPrint = true }
    suspend fun save(name: String, content: String) = withContext(Dispatchers.IO) {
        Files.writeString(directory.resolve(name), content, StandardOpenOption.CREATE_NEW)
    }
    val store = SqlDelightTranscriptStore.open(directory.resolve("runtime.db"))
    try {
        val session = "CLI:quality-main"
        val source = store.promptHistory(session, requiredTailTurns = 2, tokenBudget = 4_096)
        if (args[0] == "prepare") {
            require(source.stableChunks.isNotEmpty()) { "No sealed history to compact" }
            save("compaction-source.json", json.encodeToString(source))
            val instructions = withContext(Dispatchers.IO) {
                checkNotNull(Thread.currentThread().contextClassLoader
                    .getResourceAsStream("evaluation/prompt-history-compaction.txt"))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val request = buildJsonObject {
                put("model", "gpt-6-luna")
                put("instructions", instructions)
                put("input", JsonArray(listOf(buildJsonObject {
                    put("role", "user")
                    put("content", json.encodeToString(source.copy(mutableTail = emptyList())))
                })))
                put("reasoning", buildJsonObject { put("effort", "medium") })
                put("prompt_cache_key", "openeden-p2-compaction-${source.cacheEpoch}")
            }
            save("compaction-request.json", json.encodeToString(request))
        } else {
            val (expected, response) = withContext(Dispatchers.IO) {
                json.decodeFromString<PromptHistorySnapshot>(Files.readString(directory.resolve("compaction-source.json"))) to
                    json.parseToJsonElement(Files.readString(directory.resolve("compaction-response.json"))).jsonObject
            }
            check(expected == source) { "History changed since preparation" }
            check(response["status"]?.jsonPrimitive?.content == "completed")
            val payload = response["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: response["output"]!!.jsonArray.flatMap { item ->
                item.jsonObject["content"]?.jsonArray.orEmpty()
            }.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
                .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
            val result = store.compactPromptHistory(
                session, args.getOrNull(2) ?: "p2-controlled-compaction", 2, 4_096,
                PromptHistoryCompactor.validated(requireSourceAnchors = true) { payload },
            )
            check(result.cacheEpoch == source.cacheEpoch + 1) { "Compaction was retained without advancing epoch" }
            check(result.mutableTail == source.mutableTail)
            check(result.summary!!.sourceTurnIds == source.stableChunks.flatMap { it.turnIds }.toSet() +
                source.summary?.sourceTurnIds.orEmpty())
            save("compaction-result.json", json.encodeToString(result))
        }
    } finally {
        store.close()
    }
    println("Isolated compaction ${args[0]} completed")
}
