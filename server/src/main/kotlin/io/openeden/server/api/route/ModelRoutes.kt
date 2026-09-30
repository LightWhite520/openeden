package io.openeden.server.api.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import io.openeden.server.llm.LlmModelCatalog
import io.openeden.server.auth.ChatGptAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest

val LlmModelCatalogKey = AttributeKey<LlmModelCatalog>("openeden.llm-model-catalog")

internal fun Route.installModelRoutes(catalog: LlmModelCatalog?, token: String?) {
    suspend fun ApplicationCall.authorized(): Boolean {
        if (token.isNullOrBlank() || catalog == null) { respond(HttpStatusCode.NotFound); return false }
        val provided = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
        if (!MessageDigest.isEqual(provided.toByteArray(), token.toByteArray())) {
            respond(HttpStatusCode.Unauthorized); return false
        }
        return true
    }
    suspend fun ApplicationCall.respondCatalog(select: Boolean) {
        if (!authorized()) return
        try {
            val models = if (select) {
                val model = receive<JsonObject>()["model"]?.jsonPrimitive?.content.orEmpty()
                require(model.isNotBlank() && model.length <= 512)
                catalog!!.select(model)
            } else catalog!!.fetch()
            val current = catalog.current()
            respond(buildJsonObject {
                put("current", current)
                putJsonArray("models") {
                    models.forEach { (id, name) -> add(buildJsonObject { put("id", id); put("name", name) }) }
                }
            })
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ChatGptAuthException) { throw failure
        } catch (_: io.ktor.server.plugins.BadRequestException) {
            respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "Invalid model selection request") })
        } catch (_: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "Choose a model from the current catalog") })
        } catch (_: Exception) {
            respond(HttpStatusCode.BadGateway, buildJsonObject { put("error", "Model catalog unavailable; check provider authentication") })
        }
    }
    get("/api/v1/models") { call.respondCatalog(false) }
    post("/api/v1/models") { call.respondCatalog(true) }
}
