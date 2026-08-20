package io.openeden.server.vector.qdrant

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QdrantClientTest {
    @Test
    fun `collection inspection and creation use collection endpoints`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            when (request.url.encodedPath) {
                "/collections/eden" -> if (request.method.value == "GET") response("{\"result\":{\"status\":\"green\"}}") else response("{}", HttpStatusCode.OK)
                else -> response("{}")
            }
        }
        assertEquals("green", client.inspectCollection("eden")?.status)
        client.createCollection("eden", vectorSize = 1536)
        assertEquals("PUT", requests[1].method.value)
        assertTrue(requests[1].body.toString().contains("\"semantic\""))
        client.close()
    }

    @Test
    fun `payload index and batch upsert send qdrant wire payloads`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { response("{}") }
        client.ensurePayloadIndex("eden", "user_id")
        client.upsertPoints("eden", listOf(QdrantPoint("p1", floatArrayOf(.1f, .2f), mapOf("user_id" to "u1"))))
        assertEquals("PUT", requests[0].method.value)
        assertTrue(requests[0].url.encodedPath.endsWith("/index"))
        assertEquals("PUT", requests[1].method.value)
        assertTrue(requests[1].body.toString().contains("\"points\""))
        client.close()
    }

    @Test
    fun `semantic search uses named vector exact filter and api key`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { response("{\"result\":[{\"id\":\"p1\",\"version\":2,\"score\":0.9,\"payload\":{\"room\":\"event_room\"}}]}") }
        val hits = client.searchSemanticPoints("eden", floatArrayOf(.1f, .2f), 5, QdrantFilter(must = listOf(QdrantFieldCondition("room", "event_room"))))
        assertEquals("p1", hits.single().id)
        assertEquals("secret", requests.single().headers["api-key"])
        val body = (requests.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(body.contains("\"name\":\"semantic\""))
        assertTrue(body.contains("\"key\":\"room\""))
        assertTrue(body.contains("\"value\":\"event_room\""))
        client.close()
    }

    @Test
    fun `timeout is reported as timeout category`() = runTest {
        val client = clientFor(mutableListOf()) { throw java.util.concurrent.TimeoutException("internal detail") }
        val failure = assertFailsWith<QdrantClientException> { client.healthProbe() }
        assertEquals(QdrantErrorCategory.TIMEOUT, failure.category)
        client.close()
    }

    @Test
    fun `non success and malformed json become sanitized categories`() = runTest {
        val http = clientFor(mutableListOf()) { respondError(HttpStatusCode.BadGateway) }
        val failure = assertFailsWith<QdrantClientException> { http.inspectCollection("eden") }
        assertEquals(QdrantErrorCategory.HTTP, failure.category)
        assertTrue(!failure.message.orEmpty().contains("internal token"))
        http.close()

        val malformed = clientFor(mutableListOf()) { response("{oops") }
        val jsonFailure = assertFailsWith<QdrantClientException> { malformed.inspectCollection("eden") }
        assertEquals(QdrantErrorCategory.MALFORMED_JSON, jsonFailure.category)
        malformed.close()
    }

    @Test
    fun `cancellation is always rethrown`() = runTest {
        val client = clientFor(mutableListOf()) { throw CancellationException("cancelled") }
        val job = launch { client.healthProbe() }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        client.close()
    }

    private fun clientFor(requests: MutableList<HttpRequestData>, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        QdrantClient("http://qdrant", apiKey = "secret", timeoutMillis = 0, engine = MockEngine { request -> requests += request; handler(request) })

    private fun MockRequestHandleScope.response(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
}
