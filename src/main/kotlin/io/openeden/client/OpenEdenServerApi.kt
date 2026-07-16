package io.openeden.client

import kotlinx.coroutines.flow.Flow

interface OpenEdenServerApi {
    suspend fun health(): Boolean
    suspend fun chat(userId: String, text: String): ChatResponse
    fun chatStream(userId: String, text: String, clientRequestId: String): Flow<ChatStreamEvent>
    suspend fun state(userId: String): PublicState
    suspend fun diagnostics(userId: String, token: String): DiagnosticState
    fun close()
}
