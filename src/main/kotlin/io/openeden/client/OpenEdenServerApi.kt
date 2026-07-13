package io.openeden.client

interface OpenEdenServerApi {
    suspend fun health(): Boolean
    suspend fun chat(userId: String, text: String): ChatResponse
    suspend fun state(userId: String): PublicState
    fun close()
}
