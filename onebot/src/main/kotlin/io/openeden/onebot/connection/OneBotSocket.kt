package io.openeden.onebot.connection

interface OneBotSocket {
    suspend fun send(text: String)
    suspend fun close(reason: String)
}
