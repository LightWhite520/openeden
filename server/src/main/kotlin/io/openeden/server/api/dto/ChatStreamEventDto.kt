package io.openeden.server.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatStreamEventDto {
    @Serializable
    @SerialName("accepted")
    data class Accepted(val requestId: String) : ChatStreamEventDto

    @Serializable
    @SerialName("stage")
    data class Stage(val stage: String) : ChatStreamEventDto

    @Serializable
    @SerialName("response.delta")
    data class ResponseDelta(val text: String) : ChatStreamEventDto

    @Serializable
    @SerialName("completed")
    data class Completed(val requestId: String, val status: String) : ChatStreamEventDto

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String, val retryable: Boolean) : ChatStreamEventDto
}
