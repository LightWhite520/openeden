package io.openeden.server.vector.qdrant

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class QdrantClient(
    baseUrl: String,
    private val apiKey: String? = null,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    engine: HttpClientEngine = CIO.create(),
) : AutoCloseable {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClient(engine) {
        install(ContentNegotiation) { json(this@QdrantClient.json) }
        if (timeoutMillis > 0) install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
        defaultRequest {
            apiKey?.let { header("api-key", it) }
        }
    }

    suspend fun inspectCollection(name: String): QdrantCollection? = request {
        val response = http.get(collectionPath(name))
        if (response.status == HttpStatusCode.NotFound) return@request null
        response.requireSuccess().decode<QdrantCollectionResponse>().result?.toModel()
    }

    suspend fun createCollection(name: String, vectors: Map<String, QdrantVectorSpec>) {
        request {
            http.put(collectionPath(name)) {
                contentType(ContentType.Application.Json)
                setBody(QdrantCreateCollectionRequest(vectors.mapValues { (_, spec) -> QdrantVectorConfig(spec.size, spec.distance) }))
            }.requireSuccess()
        }
    }

    suspend fun createCollection(name: String, semanticSize: Int, emotionalSize: Int) = createCollection(
        name,
        mapOf("semantic" to QdrantVectorSpec(semanticSize), "emotional" to QdrantVectorSpec(emotionalSize)),
    )

    suspend fun ensurePayloadIndex(collection: String, fieldName: String, schemaType: String = "keyword") {
        request {
            http.put("${collectionPath(collection)}/index") {
                contentType(ContentType.Application.Json)
                setBody(QdrantPayloadIndexRequest(fieldName, schemaType))
            }.requireSuccess()
        }
    }

    suspend fun upsertPoints(collection: String, points: List<QdrantPoint>) {
        request {
            http.put("${collectionPath(collection)}/points") {
                contentType(ContentType.Application.Json)
                setBody(QdrantUpsertRequest(points.map { it.toWire() }))
            }.requireSuccess()
        }
    }

    suspend fun deletePoints(collection: String, pointIds: List<String>) {
        if (pointIds.isEmpty()) return
        request {
            http.post("${collectionPath(collection)}/points/delete") {
                contentType(ContentType.Application.Json)
                setBody(QdrantDeletePointsRequest(points = pointIds))
            }.requireSuccess()
        }
    }

    suspend fun deletePoints(collection: String, filter: QdrantFilter) {
        request {
            http.post("${collectionPath(collection)}/points/delete") {
                contentType(ContentType.Application.Json)
                setBody(QdrantDeletePointsRequest(filter = filter.toWire()))
            }.requireSuccess()
        }
    }

    /** Qdrant's named-vector search shape is {vector:{name,vector}, limit, filter}. */
    suspend fun searchSemanticPoints(collection: String, vector: FloatArray, limit: Int, filter: QdrantFilter? = null, using: String = "semantic"): List<QdrantSearchHit> = request {
        val response = http.post("${collectionPath(collection)}/points/search") {
            contentType(ContentType.Application.Json)
            setBody(QdrantSearchRequest(QdrantNamedVector(using, vector.toList()), limit, filter?.toWire()))
        }.requireSuccess()
        response.decode<QdrantSearchResponse>().result.map { it.toModel() }
    }

    suspend fun healthProbe(): Boolean = request {
        http.get("$baseUrl/healthz").requireSuccess()
        true
    }

    override fun close() { http.close() }

    private suspend fun <T> request(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (timeout: HttpRequestTimeoutException) {
        throw QdrantClientException(QdrantErrorCategory.TIMEOUT, message = "Qdrant request timed out", cause = timeout)
    } catch (timeout: java.util.concurrent.TimeoutException) {
        throw QdrantClientException(QdrantErrorCategory.TIMEOUT, message = "Qdrant request timed out", cause = timeout)
    } catch (failure: QdrantClientException) {
        throw failure
    } catch (failure: kotlinx.serialization.SerializationException) {
        throw QdrantClientException(QdrantErrorCategory.MALFORMED_JSON, message = "Qdrant response was malformed", cause = failure)
    } catch (failure: Exception) {
        throw QdrantClientException(QdrantErrorCategory.NETWORK, message = "Qdrant request failed", cause = failure)
    }

    private fun HttpResponse.requireSuccess(): HttpResponse {
        if (status.value !in 200..299) throw QdrantClientException(QdrantErrorCategory.HTTP, status.value, "Qdrant returned HTTP ${status.value}")
        return this
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())

    private fun collectionPath(name: String) = "$baseUrl/collections/${java.net.URLEncoder.encode(name, Charsets.UTF_8)}"

    private companion object { const val DEFAULT_TIMEOUT_MILLIS = 10_000L }
}

@Serializable private data class QdrantVectorConfig(val size: Int, val distance: String)
@Serializable private data class QdrantCreateCollectionRequest(val vectors: Map<String, QdrantVectorConfig>)
@Serializable private data class QdrantPayloadIndexRequest(val field_name: String, val field_schema: String)
@Serializable private data class QdrantWirePoint(val id: String, val vector: Map<String, List<Float>>, val payload: Map<String, String>)
@Serializable private data class QdrantUpsertRequest(val points: List<QdrantWirePoint>)
@Serializable private data class QdrantDeletePointsRequest(
    val points: List<String>? = null,
    val filter: QdrantWireFilter? = null,
)
@Serializable private data class QdrantNamedVector(val name: String, val vector: List<Float>)
@Serializable private data class QdrantSearchRequest(val vector: QdrantNamedVector, val limit: Int, val filter: QdrantWireFilter? = null)
@Serializable private data class QdrantWireFilter(val must: List<QdrantWireCondition>)
@Serializable private data class QdrantWireCondition(val key: String, val match: QdrantMatch)
@Serializable private data class QdrantMatch(val value: String)
@Serializable private data class QdrantCollectionResponse(val result: QdrantCollectionWire? = null)
@Serializable private data class QdrantCollectionWire(val status: String? = null, val config: QdrantCollectionConfig? = null)
@Serializable private data class QdrantCollectionConfig(val params: QdrantCollectionParams? = null)
@Serializable private data class QdrantCollectionParams(val vectors: Map<String, QdrantVectorConfig> = emptyMap())
@Serializable private data class QdrantSearchResponse(val result: List<QdrantHitWire> = emptyList())
@Serializable private data class QdrantHitWire(val id: String, val score: Double, val payload: Map<String, String> = emptyMap())

private fun QdrantPoint.toWire() = QdrantWirePoint(id, vectors.mapValues { (_, vector) -> vector.toList() }, payload)
private fun QdrantFilter.toWire() = QdrantWireFilter(must.map { QdrantWireCondition(it.key, QdrantMatch(it.value)) })
private fun QdrantCollectionWire.toModel() = QdrantCollection(status, config?.params?.vectors.orEmpty().mapValues { (_, spec) -> QdrantVectorSpec(spec.size, spec.distance) })
private fun QdrantHitWire.toModel() = QdrantSearchHit(id, score, payload)
