package io.openeden.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class LlmModelCatalogTest {
    @Test
    fun `subscription permits omitted model only after successful inference probe`() = runTest {
        val accounts = io.openeden.server.auth.ChatGptAccountStore(Files.createTempDirectory("catalog-account-"))
        accounts.locked { state, save -> save(state.copy(activeClientId = "registration", accounts = listOf(
            io.openeden.server.auth.ChatGptAccount("registration", "subject", null, "access", "refresh",
                expiresAtMs = System.currentTimeMillis() + 3_600_000,
                scopes = setOf(io.openeden.server.auth.ChatGptOAuth.PLAN_SCOPE, "resource.invoke")),
        ))) }
        val oauth = io.openeden.server.auth.ChatGptOAuth(accounts, HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/models")) respond("""{"models":[{"slug":"listed","display_name":"Listed","visibility":"list"}]}""")
            else if (request.body.toByteArray().decodeToString().contains("\"model\":\"omitted\"")) {
                respond("data: {\"type\":\"response.completed\",\"response\":{}}\n\n")
            } else respond("unavailable", HttpStatusCode.BadRequest)
        }))
        oauth.use {
            LlmModelCatalog("", "https://api.openai.com/v1", "listed", oauth,
                ModelSelectionStore(Files.createTempDirectory("catalog-choice-")), accounts).use { catalog ->
                assertEquals(listOf("listed"), catalog.fetch().map { it.first })
                catalog.select("omitted")
                assertEquals("omitted", catalog.current())
                assertFailsWith<IllegalArgumentException> { catalog.select("unavailable") }
                assertEquals("omitted", catalog.current())
            }
        }
    }
    @Test
    fun `fetch preserves order and selection survives restart but stays provider and account scoped`() = runTest {
        val store = ModelSelectionStore(Files.createTempDirectory("model-selection-"))
        fun catalog(key: String = "key", url: String = "https://provider.test/v1") = LlmModelCatalog(
            key, url, "default", selections = store, client = HttpClient(MockEngine { request ->
                assertEquals("$url/models", request.url.toString())
                assertEquals("Bearer $key", request.headers[HttpHeaders.Authorization])
                respond("""{"data":[{"id":"second"},{"id":"first"},{"id":"second"}]}""")
            }))
        catalog().use { c ->
            assertEquals(listOf("second", "first"), c.fetch().map { it.first })
            c.select("first")
            assertFailsWith<IllegalArgumentException> { c.select("missing") }
            assertEquals("first", c.current())
        }
        catalog().use { assertEquals("first", it.current()) }
        catalog(key = "other").use { assertEquals("default", it.current()) }
        catalog(url = "https://other.test/v1").use { assertEquals("default", it.current()) }
    }

    @Test
    fun `failed fetch keeps saved model and never substitutes default`() = runTest {
        val store = ModelSelectionStore(Files.createTempDirectory("model-selection-"))
        store.write("api:https://provider.test/v1:key", "saved")
        LlmModelCatalog("key", "https://provider.test/v1", "default", selections = store,
            client = HttpClient(MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) })).use {
            assertFailsWith<IllegalStateException> { it.fetch() }
            assertFailsWith<IllegalStateException> { it.select("new") }
            assertEquals("saved", it.current())
        }
    }
}
