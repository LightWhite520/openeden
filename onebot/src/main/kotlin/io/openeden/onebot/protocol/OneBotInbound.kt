package io.openeden.onebot.protocol

sealed interface OneBotInbound {
    data class Message(val event: OneBotMessageEvent) : OneBotInbound
    data class Action(val response: OneBotActionResponse) : OneBotInbound
    data class Ignored(val reason: String) : OneBotInbound
}
