package io.openeden.onebot.ingress

import io.openeden.runtime.pipeline.DevelopmentMessageRequest

fun interface OneBotMessageHandler {
    suspend fun handle(request: DevelopmentMessageRequest): OneBotMessageResult
}
