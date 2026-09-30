package io.openeden.server.api.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.openeden.server.api.plugin.configureSerialization
import io.openeden.server.llm.LlmModelCatalog
import io.openeden.server.llm.ModelSelectionStore
import java.nio.file.Files
import kotlin.test.*

class ModelRoutesTest {
    @Test
    fun `model management requires token and fetches and persists validated selection`() = testApplication {
        var fetches = 0
        val catalog = LlmModelCatalog("key", "https://provider.test/v1", "a",
            selections = ModelSelectionStore(Files.createTempDirectory("model-route-")),
            client = HttpClient(MockEngine { fetches++; respond("""{"data":[{"id":"a"},{"id":"b"}]}""") }))
        application { configureSerialization(); routing { installModelRoutes(catalog, "secret") } }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/models").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/models") { bearerAuth("wrong") }.status)
            assertEquals(0, fetches)
            val list = client.get("/api/v1/models") { bearerAuth("secret") }
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("\"current\":\"a\""))
            val selected = client.post("/api/v1/models") {
                bearerAuth("secret"); contentType(ContentType.Application.Json); setBody("""{"model":"b"}""")
            }
            assertEquals(HttpStatusCode.OK, selected.status)
            assertEquals("b", catalog.current())
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/models") {
                bearerAuth("secret"); contentType(ContentType.Application.Json); setBody("""{"model":"missing"}""")
            }.status)
            assertEquals("b", catalog.current())
        } finally { catalog.close() }
    }

    @Test
    fun `model management is hidden without an operator token`() = testApplication {
        application { routing { installModelRoutes(null, null) } }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/models").status)
    }
}
