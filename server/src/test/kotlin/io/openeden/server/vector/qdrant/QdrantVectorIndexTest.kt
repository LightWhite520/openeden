package io.openeden.server.vector.qdrant

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.VectorSearchRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QdrantVectorIndexTest {
    @Test
    fun `first valid insert creates compatible named vectors indexes and payload`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        index.insert(entry("memory-1", semantic = listOf(.1f, .2f), emotional = listOf(.3f, .4f, .5f)))

        assertEquals(7, requests.size)
        val create = requests[1].jsonBody()
        assertEquals(2, create["vectors"]!!.jsonObject.size)
        assertEquals(2, create["vectors"]!!.jsonObject["semantic"]!!.jsonObject["size"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, create["vectors"]!!.jsonObject["emotional"]!!.jsonObject["size"]!!.jsonPrimitive.content.toInt())
        assertTrue(requests.drop(2).take(4).all { it.url.encodedPath.endsWith("/index") })
        val point = requests.last().jsonBody()["points"]!!.jsonArray.single().jsonObject
        assertEquals("memory-1", point["payload"]!!.jsonObject["memory_id"]!!.jsonPrimitive.content)
        assertEquals("local-v1", point["payload"]!!.jsonObject["model_id"]!!.jsonPrimitive.content)
        assertEquals(2, point["vector"]!!.jsonObject.size)
        client.close()
    }

    @Test
    fun `search sends exact session room kind and model filters and returns null entries`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") {
                response("""{"result":{"config":{"params":{"vectors":{"semantic":{"size":2,"distance":"Cosine"},"emotional":{"size":3,"distance":"Cosine"}}}}}}""")
            } else {
                response("""{"result":[{"id":"point-1","score":0.91,"payload":{"memory_id":"memory-1"}}]}""")
            }
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        val hits = index.search(VectorSearchRequest("QQ:42", listOf(.1f, .2f), room = MemoryRoom.EVENT_ROOM, kind = MemoryKind.RAW, limit = 6))

        assertEquals(1, hits.size)
        assertEquals("memory-1", hits.single().memoryId)
        assertEquals(null, hits.single().entry)
        assertEquals(.91f, hits.single().semanticSimilarity)
        val filter = requests.last().jsonBody()["filter"]!!.jsonObject["must"]!!.jsonArray
        assertEquals(listOf("QQ:42", "EVENT_ROOM", "RAW", "local-v1"), filter.map { it.jsonObject["match"]!!.jsonObject["value"]!!.jsonPrimitive.content })
        client.close()
    }

    @Test
    fun `invalid vectors are rejected before any network request`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { response("{}") }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        assertFailsWith<IllegalArgumentException> { index.insert(entry("empty", emptyList(), listOf(.1f))) }
        assertFailsWith<IllegalArgumentException> { index.insert(entry("nan", listOf(Float.NaN), listOf(.1f))) }
        assertFailsWith<IllegalArgumentException> { index.insert(entry("zero", listOf(0f), listOf(.1f))) }
        assertEquals(0, requests.size)
        client.close()
    }

    @Test
    fun `vectors with dimensions incompatible with established collection are rejected before network`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")
        index.insert(entry("first", listOf(.1f, .2f), listOf(.3f, .4f)))
        val callsAfterFirst = requests.size

        assertFailsWith<IllegalArgumentException> { index.insert(entry("second", listOf(.1f), listOf(.3f, .4f))) }
        assertEquals(callsAfterFirst, requests.size)
        client.close()
    }

    @Test
    fun `remove deletes the deterministic qdrant point`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { response("{}") }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        index.remove("memory-1")

        assertEquals("POST", requests.single().method.value)
        assertTrue(requests.single().url.encodedPath.endsWith("/points/delete"))
        assertEquals(
            QdrantPointIds.fromMemoryId("memory-1"),
            requests.single().jsonBody()["points"]!!.jsonArray.single().jsonPrimitive.content,
        )
        client.close()
    }

    private fun entry(id: String, semantic: List<Float>, emotional: List<Float>) = MemoryEntry(
        id = id,
        sessionId = "QQ:42",
        content = "content",
        room = MemoryRoom.EVENT_ROOM,
        kind = MemoryKind.RAW,
        semanticEmbedding = semantic,
        emotionalEmbedding = emotional,
        metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "u1"),
    )

    private fun clientFor(
        requests: MutableList<HttpRequestData>,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = QdrantClient(
        "http://qdrant",
        timeoutMillis = 0,
        engine = MockEngine { request -> requests += request; handler(request) },
    )

    private fun MockRequestHandleScope.response(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement(
        (body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
    ).jsonObject
}
