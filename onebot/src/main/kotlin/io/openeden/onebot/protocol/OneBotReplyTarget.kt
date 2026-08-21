package io.openeden.onebot.protocol

sealed interface OneBotReplyTarget {
    data class Private(val userId: String) : OneBotReplyTarget
    data class Group(val groupId: String) : OneBotReplyTarget
}
