package io.openeden.trace

interface TraceStore {
    suspend fun append(span: TraceSpan)
}
