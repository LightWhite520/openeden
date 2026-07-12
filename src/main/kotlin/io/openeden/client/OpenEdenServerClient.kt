package io.openeden.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable

interface OpenEdenServerApi {
    suspend fun health(): Boolean
    suspend fun chat(userId: String, text: String): ChatResponse
    suspend fun state(userId: String): PublicState
    fun close()
}

class OpenEdenServerClient(
    baseUrl: String,
    private val httpClient: HttpClient,
) : OpenEdenServerApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun health(): Boolean = runCatching {
        val response = httpClient.get("$baseUrl/health")
        response.requireSuccess()
        true
    }.getOrDefault(false)

    override suspend fun chat(userId: String, text: String): ChatResponse =
        httpClient.post("$baseUrl/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(userId = userId, text = text))
        }.decodeSuccess()

    override suspend fun state(userId: String): PublicState =
        httpClient.get("$baseUrl/api/v1/state?userId=${userId.encodeURLParameter()}")
            .decodeSuccess()

    override fun close() {
        httpClient.close()
    }

    private suspend inline fun <reified T> HttpResponse.decodeSuccess(): T {
        requireSuccess()
        return body()
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value in 200..299) return
        val detail = runCatching { body<String>() }.getOrNull().orEmpty()
        throw ServerClientException(status, detail.ifBlank { status.description })
    }
}

@Serializable
data class ChatRequest(
    val userId: String,
    val text: String,
)

@Serializable
data class ChatResponse(
    val requestId: String,
    val status: String,
    val response: String? = null,
    val error: String? = null,
)

@Serializable
data class PublicState(
    val sessionId: String,
    val status: String,
    val omega: Float,
    val shockActive: Boolean,
)

class ServerClientException(
    val status: HttpStatusCode,
    detail: String,
) : IllegalStateException("OpenEden server request failed: ${status.value} ${status.description}: $detail")
