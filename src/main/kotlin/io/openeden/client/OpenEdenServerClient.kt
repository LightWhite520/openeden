package io.openeden.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    override fun chatStream(userId: String, text: String, clientRequestId: String): Flow<ChatStreamEvent> = flow {
        httpClient.preparePost("$baseUrl/api/v1/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(ChatStreamRequest(userId = userId, text = text, clientRequestId = clientRequestId))
        }.execute { response ->
            response.requireSuccess()
            SseEventParser().parse(response.bodyAsChannel()).collect(::emit)
        }
    }

    override suspend fun state(userId: String): PublicState =
        httpClient.get("$baseUrl/api/v1/state?userId=${userId.encodeURLParameter()}")
            .decodeSuccess()

    override suspend fun diagnostics(userId: String, token: String): DiagnosticState =
        httpClient.get("$baseUrl/api/v1/diagnostics?userId=${userId.encodeURLParameter()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.decodeSuccess()

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
