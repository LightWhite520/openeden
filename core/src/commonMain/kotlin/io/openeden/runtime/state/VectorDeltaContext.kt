package io.openeden.runtime.state

sealed interface VectorDeltaContext {
    data object Ordinary : VectorDeltaContext

    data class Authoritative(
        val confidence: Float,
    ) : VectorDeltaContext
}
