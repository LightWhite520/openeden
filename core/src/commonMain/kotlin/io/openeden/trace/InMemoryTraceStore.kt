package io.openeden.trace

class InMemoryTraceStore : TraceStore {
    private val spans = mutableListOf<TraceSpan>()

    override suspend fun append(span: TraceSpan) {
        spans += TraceSanitizer.sanitize(span)
    }

    fun snapshot(): List<TraceSpan> = spans.toList()
}
