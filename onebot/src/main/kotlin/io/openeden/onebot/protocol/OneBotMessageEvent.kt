package io.openeden.onebot.protocol

data class OneBotMessageEvent(
    val selfId: String,
    val messageId: String,
    val userId: String,
    val text: String,
    val target: OneBotReplyTarget,
)
