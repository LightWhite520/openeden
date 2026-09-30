package io.openeden.server.evaluation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.openeden.llm.ChatGptSubscriptionRequests
import io.openeden.llm.completedResponsesStream
import io.openeden.server.auth.ChatGptOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Offline operator entry point for auditable real-provider evaluation requests, with no tool execution. */
suspend fun main(args: Array<String>) {
    require(args.size == 2 || (args.size == 3 && args[2] == "--without-session-affinity")) {
        "Specify a request JSON file, a new output JSON file, and optionally --without-session-affinity for a cache diagnostic control"
    }
    val requestPath = Path.of(args[0])
    val outputPath = Path.of(args[1])
    val body = withContext(Dispatchers.IO) {
        require(!Files.exists(outputPath)) { "Evaluation output already exists" }
        Json.parseToJsonElement(Files.readString(requestPath)).jsonObject
    }
    require(body["tools"] == null) { "Evaluation requests must not execute tools" }
    ChatGptOAuth().use { oauth ->
        HttpClient(CIO) {
            followRedirects = false
            // A full long-conversation judge includes per-turn annotations and substantial SSE framing.
            install(HttpTimeout) { requestTimeoutMillis = 600_000 }
        }.use { client ->
            val response = client.post("${ChatGptSubscriptionRequests.BASE_URL}/responses") {
                bearerAuth(oauth.accessToken())
                if (args.size == 2) {
                    body["prompt_cache_key"]?.jsonPrimitive?.contentOrNull?.let {
                        header(ChatGptSubscriptionRequests.SESSION_ID_HEADER, it)
                    }
                }
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(ChatGptSubscriptionRequests.adapt(body).toString())
            }
            check(response.status.isSuccess()) { "Evaluation provider returned HTTP ${response.status.value}" }
            val completed = completedResponsesStream(response.bodyAsChannel(), 16 * 1024 * 1024)
            withContext(Dispatchers.IO) {
                outputPath.toAbsolutePath().parent?.let(Files::createDirectories)
                Files.writeString(outputPath, completed, StandardOpenOption.CREATE_NEW)
            }
            println("Saved completed evaluation response")
        }
    }
}
