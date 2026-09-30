package io.openeden.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.openeden.server.auth.ChatGptAccountStore
import io.openeden.server.auth.ChatGptOAuth
import kotlinx.serialization.json.*

/** Catalog order is the provider's order. Selecting a model never switches billing/auth mode. */
class LlmModelCatalog(
    private val apiKey: String,
    private val baseUrl: String,
    private val defaultModel: String,
    private val subscription: ChatGptOAuth? = null,
    private val selections: ModelSelectionStore = ModelSelectionStore(),
    private val accountStore: ChatGptAccountStore = ChatGptAccountStore(),
    private val client: HttpClient = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
) : AutoCloseable {
    private suspend fun scope(): String = if (subscription != null) {
        "chatgpt:${requireNotNull(accountStore.read().activeClientId) { "Sign in to ChatGPT first" }}"
    } else "api:${baseUrl.trimEnd('/')}:$apiKey"

    suspend fun current(): String = selections.read(scope()) ?: defaultModel

    suspend fun fetch(): List<Pair<String, String>> {
        val models = if (subscription != null) subscription.models() else {
            val response = client.get("${baseUrl.trimEnd('/')}/models") { bearerAuth(apiKey) }
            check(response.status.isSuccess()) { "Model list fetch failed: HTTP ${response.status.value}" }
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonArray.map {
                val id = it.jsonObject.getValue("id").jsonPrimitive.content
                id to id
            }
        }
        return models.filter { (id, _) -> id.isNotBlank() && id.length <= 512 && id.none(Char::isISOControl) }.distinctBy { it.first }
    }

    suspend fun select(model: String): List<Pair<String, String>> {
        require(model.isNotBlank() && model.length <= 512 && model.none(Char::isISOControl))
        val originalScope = scope()
        val models = fetch()
        if (models.none { it.first == model }) {
            require(subscription != null) { "Model is not in the current provider catalog" }
            require(subscription.probeModel(model) in 200..299) { "Explicit model is unavailable to this ChatGPT account" }
        }
        check(scope() == originalScope) { "Account changed; fetch the model list again" }
        selections.write(originalScope, model)
        return models
    }

    override fun close() = client.close()
}
