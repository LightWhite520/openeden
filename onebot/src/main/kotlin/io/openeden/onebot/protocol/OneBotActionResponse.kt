package io.openeden.onebot.protocol

data class OneBotActionResponse(
    val status: String,
    val retCode: Int,
    val echo: String,
)
