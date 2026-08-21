package io.openeden.onebot.egress

class OneBotActionException(
    val category: Category,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Category { DISCONNECTED, TIMEOUT, REJECTED, TRANSPORT }
}
