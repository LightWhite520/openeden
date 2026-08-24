package io.openeden.server.vector.qdrant

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.VectorSearchRequest
import io.openeden.trace.TraceTag
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

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
    fun `collection creation emits an operational trace tag`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val tags = mutableListOf<String>()
        val index = QdrantVectorIndex(
            client = client,
            naming = QdrantCollectionNaming("eden"),
            modelId = "local-v1",
            onTrace = tags::add,
        )

        index.insert(entry("memory-1", semantic = listOf(.1f, .2f), emotional = listOf(.3f, .4f, .5f)))

        assertEquals(listOf(TraceTag.VectorCollectionCreated), tags)
        client.close()
    }

    @Test
    fun `compatible existing collection ensures all payload indexes before upsert`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") {
                response("""{"result":{"config":{"params":{"vectors":{"semantic":{"size":2,"distance":"Cosine"},"emotional":{"size":3,"distance":"Cosine"}}}}}}""")
            } else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        index.insert(entry("memory-1", semantic = listOf(.1f, .2f), emotional = listOf(.3f, .4f, .5f)))

        val collectionPath = "/collections/${QdrantCollectionNaming("eden").collectionName("local-v1")}"
        assertEquals(listOf(collectionPath, "$collectionPath/index", "$collectionPath/index", "$collectionPath/index", "$collectionPath/index", "$collectionPath/points"), requests.map { it.url.encodedPath })
        client.close()
    }

    @Test
    fun `rebuild clears active model points before streaming bounded upserts`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        index.rebuild(
            listOf(
                entry("memory-1", listOf(.1f, .2f), listOf(.3f, .4f, .5f)),
                entry("memory-2", listOf(.2f, .3f), listOf(.4f, .5f, .6f)),
                entry("memory-3", listOf(.3f, .4f), listOf(.5f, .6f, .7f)),
            ),
            batchSize = 2,
        )

        val delete = requests[6].jsonBody()
        assertTrue(requests[6].url.encodedPath.endsWith("/points/delete"))
        assertEquals("local-v1", delete["filter"]!!.jsonObject["must"]!!.jsonArray.single().jsonObject["match"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(2, requests[7].jsonBody()["points"]!!.jsonArray.size)
        assertEquals(1, requests[8].jsonBody()["points"]!!.jsonArray.size)
        client.close()
    }

    @Test
    fun `empty rebuild clears an existing compatible collection`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") {
                response("""{"result":{"config":{"params":{"vectors":{"semantic":{"size":2,"distance":"Cosine"},"emotional":{"size":3,"distance":"Cosine"}}}}}}""")
            } else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        index.rebuild(emptyList())

        assertTrue(requests.last().url.encodedPath.endsWith("/points/delete"))
        client.close()
    }

    @Test
    fun `rebuild consumes a one shot iterable exactly once`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")
        val entries = listOf(
            entry("memory-1", listOf(.1f, .2f), listOf(.3f, .4f, .5f)),
            entry("memory-2", listOf(.2f, .3f), listOf(.4f, .5f, .6f)),
        )
        var iteratorCalls = 0
        val oneShot = object : Iterable<MemoryEntry> {
            override fun iterator(): Iterator<MemoryEntry> {
                check(iteratorCalls++ == 0) { "rebuild iterated more than once" }
                return entries.iterator()
            }
        }

        index.rebuild(oneShot, batchSize = 1)

        assertEquals(2, requests.count { it.url.encodedPath.endsWith("/points") })
        client.close()
    }

    @Test
    fun `rebuild replacement excludes concurrent insert until all upserts finish`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val client = clientFor(requests) { request ->
            if (request.url.encodedPath.endsWith("/points/delete")) {
                deleteStarted.complete(Unit)
                releaseDelete.await()
            }
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")
        val rebuildJob = async {
            index.rebuild(listOf(entry("memory-1", listOf(.1f, .2f), listOf(.3f, .4f, .5f))), batchSize = 1)
        }
        deleteStarted.await()
        val insertJob = async {
            index.insert(entry("memory-2", listOf(.2f, .3f), listOf(.4f, .5f, .6f)))
        }
        yield()
        assertTrue(!insertJob.isCompleted)
        releaseDelete.complete(Unit)
        rebuildJob.await()
        insertJob.await()

        assertEquals(2, requests.count { it.url.encodedPath.endsWith("/points") })
        client.close()
    }

    @Test
    fun `mid stream invalid vector fails before any network request`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        assertFailsWith<IllegalArgumentException> {
            index.rebuild(
                listOf(
                    entry("memory-1", listOf(.1f, .2f), listOf(.3f, .4f, .5f)),
                    entry("invalid", listOf(.2f), listOf(.4f, .5f, .6f)),
                ),
                batchSize = 1,
            )
        }

        assertEquals(0, requests.size)
        client.close()
    }

    @Test
    fun `cancellation cleans rebuild snapshot and closes the remote replacement`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val deleteStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = clientFor(requests) { request ->
            if (request.url.encodedPath.endsWith("/points/delete")) {
                deleteStarted.complete(Unit)
                gate.await()
            }
            if (request.method.value == "GET") response("{}", HttpStatusCode.NotFound) else response("{}")
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")
        val before = snapshotFiles()
        val job = async {
            index.rebuild(listOf(entry("memory-1", listOf(.1f, .2f), listOf(.3f, .4f, .5f))), batchSize = 1)
        }

        deleteStarted.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(before, snapshotFiles())
        gate.complete(Unit)
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
    fun `search queries semantic and emotional named vectors and unions their scores`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientFor(requests) { request ->
            if (request.method.value == "GET") {
                response("""{"result":{"config":{"params":{"vectors":{"semantic":{"size":2,"distance":"Cosine"},"emotional":{"size":3,"distance":"Cosine"}}}}}}""")
            } else if (request.url.encodedPath.endsWith("/points/search")) {
                val body = request.jsonBody()
                when (body["vector"]!!.jsonObject["name"]!!.jsonPrimitive.content) {
                    "semantic" -> response("""{"result":[{"id":"point-1","score":0.9,"payload":{"memory_id":"memory-1"}},{"id":"point-2","score":0.8,"payload":{"memory_id":"memory-2"}}]}""")
                    "emotional" -> response("""{"result":[{"id":"point-1","score":0.7,"payload":{"memory_id":"memory-1"}},{"id":"point-3","score":0.6,"payload":{"memory_id":"memory-3"}}]}""")
                    else -> error("unexpected named vector")
                }
            } else {
                response("{}")
            }
        }
        val index = QdrantVectorIndex(client, QdrantCollectionNaming("eden"), "local-v1")

        val hits = index.search(
            VectorSearchRequest(
                sessionId = "QQ:42",
                semanticEmbedding = listOf(.1f, .2f),
                emotionalEmbedding = listOf(.3f, .4f, .5f),
                limit = 6,
            ),
        )

        val searchRequests = requests.filter { it.url.encodedPath.endsWith("/points/search") }
        assertEquals(listOf("semantic", "emotional"), searchRequests.map { it.jsonBody()["vector"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("memory-1", "memory-2", "memory-3"), hits.map { it.memoryId })
        assertEquals(.9f, hits[0].semanticSimilarity)
        assertEquals(.7f, hits[0].emotionalSimilarity)
        assertEquals(0.0f, hits[1].emotionalSimilarity)
        assertEquals(0.0f, hits[2].semanticSimilarity)
        assertEquals(.6f, hits[2].emotionalSimilarity)
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

    private fun snapshotFiles(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { files ->
        files.filter { it.name.startsWith("openeden-qdrant-rebuild-") && it.name.endsWith(".bin") }
            .collect(java.util.stream.Collectors.toSet())
    }
}
