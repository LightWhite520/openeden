package io.openeden.trace

data class TraceContext(
    val traceId: String,
    val turnId: String,
    val sessionId: String,
)
