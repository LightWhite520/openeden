package io.openeden.trace

enum class TraceStatus { STARTED, OK, DEGRADED, FAILED }

data class TraceContext(
    val traceId: String,
    val turnId: String,
    val sessionId: String,
)

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

interface TraceStore {
    suspend fun append(span: TraceSpan)
}

object TraceSanitizer {
    private val secretKeys = setOf("authorization", "api_key", "token", "password", "credential")

    fun sanitize(span: TraceSpan): TraceSpan = span.copy(
        attributes = span.attributes
            .filterKeys { key -> secretKeys.none { secret -> key.lowercase().contains(secret) } }
            .mapValues { (_, value) -> value.take(256) },
        errorSummary = span.errorSummary?.take(500),
    )
}

class InMemoryTraceStore : TraceStore {
    private val spans = mutableListOf<TraceSpan>()

    override suspend fun append(span: TraceSpan) {
        spans += TraceSanitizer.sanitize(span)
    }

    fun snapshot(): List<TraceSpan> = spans.toList()
}
