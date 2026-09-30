package io.openeden.server.evaluation

import io.openeden.relationship.OpenAiRelationshipEventEvaluator
import io.openeden.relationship.RelationshipTurn
import io.openeden.server.auth.ChatGptOAuth
import io.openeden.server.bootstrap.relationshipEvaluatorHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Exercise the real evaluator without fallback or writes to runtime state. */
suspend fun main(args: Array<String>) {
    require(args.size == 3) { "Specify model, turn JSON and new result JSON paths" }
    val destination = Path.of(args[2])
    val turn = withContext(Dispatchers.IO) {
        require(!Files.exists(destination)) { "Probe output already exists" }
        Json.decodeFromString<RelationshipTurn>(Files.readString(Path.of(args[1])))
    }
    ChatGptOAuth().use { oauth ->
        relationshipEvaluatorHttpClient().use { client ->
            val result = OpenAiRelationshipEventEvaluator(
                apiKey = "", model = args[0], baseUrl = "https://api.openai.com/v1",
                httpClient = client, subscriptionToken = oauth::accessToken,
                onFailure = { stage, status, failure ->
                    val streamFailure = failure as? io.openeden.llm.ResponsesStreamFailure
                    println("relationship=EVALUATOR_FAILURE stage=${stage.name} http_status=$status cause=${failure.javaClass.simpleName} terminal=${streamFailure?.terminalEvent} provider_code=${streamFailure?.providerCode}")
                },
            ).evaluate(turn)
            withContext(Dispatchers.IO) {
                Files.writeString(destination, Json.encodeToString(result), StandardOpenOption.CREATE_NEW)
            }
            println("Relationship probe completed; events=${result.events.size}")
        }
    }
}
