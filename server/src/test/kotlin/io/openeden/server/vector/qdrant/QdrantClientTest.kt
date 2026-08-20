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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                "/collections/eden" -> if (request.method.value == "GET") response("""{"result":{"status":"green","config":{"params":{"vectors":{"semantic":{"size":1536,"distance":"Cosine"},"emotional":{"size":8,"distance":"Cosine"}}}}}}""") else response("{}", HttpStatusCode.OK)
                else -> response("{}")
            }
        }
        val collection = client.inspectCollection("eden")
        assertEquals("green", collection?.status)
        assertEquals(1536, collection?.vectors?.get("semantic")?.size)
        assertEquals(8, collection?.vectors?.get("emotional")?.size)
        client.createCollection("eden", mapOf("semantic" to QdrantVectorSpec(1536), "emotional" to QdrantVectorSpec(8)))
        assertEquals("PUT", requests[1].method.value)
        val createBody = requests[1].jsonBody()
        assertEquals(1536, createBody["vectors"]!!.jsonObject["semantic"]!!.jsonObject["size"]!!.jsonPrimitive.content.toInt())
        assertEquals("Cosine", createBody["vectors"]!!.jsonObject["emotional"]!!.jsonObject["distance"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun `payload index and batch upsert send qdrant wire payloads`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { response("{}") }
        client.ensurePayloadIndex("eden", "user_id")
        client.upsertPoints("eden", listOf(QdrantPoint("p1", mapOf("semantic" to floatArrayOf(.1f, .2f), "emotional" to floatArrayOf(.3f, .4f)), mapOf("user_id" to "u1"))))
        assertEquals("PUT", requests[0].method.value)
        assertTrue(requests[0].url.encodedPath.endsWith("/index"))
        val indexBody = requests[0].jsonBody()
        assertEquals("user_id", indexBody["field_name"]!!.jsonPrimitive.content)
        assertEquals("keyword", indexBody["field_schema"]!!.jsonPrimitive.content)
        assertEquals("PUT", requests[1].method.value)
        val upsertBody = requests[1].jsonBody()
        val point = upsertBody["points"]!!.jsonArray.single().jsonObject
        assertEquals("p1", point["id"]!!.jsonPrimitive.content)
        assertTrue(point["vector"]!!.jsonObject.keys.containsAll(listOf("semantic", "emotional")))
        assertEquals("u1", point["payload"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun `semantic search uses named vector exact filter and api key`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { response("{\"result\":[{\"id\":\"p1\",\"version\":2,\"score\":0.9,\"payload\":{\"room\":\"event_room\"}}]}") }
        val hits = client.searchSemanticPoints("eden", floatArrayOf(.1f, .2f), 5, QdrantFilter(must = listOf(QdrantFieldCondition("room", "event_room"))), using = "emotional")
        assertEquals("p1", hits.single().id)
        assertEquals("secret", requests.single().headers["api-key"])
        val body = (requests.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(body.contains("\"name\":\"emotional\""))
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
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = clientFor(mutableListOf()) {
            started.complete(Unit)
            gate.await()
            response("{}")
        }
        val job = async { client.healthProbe() }
        started.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        client.close()
    }

    private fun clientFor(requests: MutableList<HttpRequestData>, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        QdrantClient("http://qdrant", apiKey = "secret", timeoutMillis = 0, engine = MockEngine { request -> requests += request; handler(request) })

    private fun MockRequestHandleScope.response(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
}
