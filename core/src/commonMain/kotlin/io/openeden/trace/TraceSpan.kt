package io.openeden.trace

data class TraceSpan(
    val context: TraceContext,
    val parentSpanId: String? = null,
    val spanId: String,
    val stage: String,
    val status: TraceStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val tags: Set<String> = emptySet(),
    val attributes: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val errorSummary: String? = null,
)
