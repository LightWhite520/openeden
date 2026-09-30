package io.openeden.trace

object TraceSanitizer {
    private val secretKeys = setOf("authorization", "api_key", "token", "password", "credential")

    fun sanitize(span: TraceSpan): TraceSpan = span.copy(
        attributes = span.attributes
            .filterKeys { key -> secretKeys.none { secret -> key.lowercase().contains(secret) } }
            .mapValues { (key, value) ->
                if (key in ContextEvidenceSanitizer.keys) ContextEvidenceSanitizer.sanitize(key, value)
                else value.take(256)
            },
        errorSummary = span.errorSummary?.take(500),
    )
}
