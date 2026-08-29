package io.openeden.prompt

import io.openeden.hash.Sha256

@ConsistentCopyVisibility
data class BuiltPrompt internal constructor(
    val segments: List<PromptSegment>,
    val cacheIdentity: String,
) {
    init {
        require(segments.map(PromptSegment::kind) == REQUIRED_ORDER) {
            "Prompt segments must use the canonical order"
        }
        require(segments.take(STABLE_PREFIX_SIZE).all { it.stability == PromptStability.STABLE }) {
            "System, persona, and incarnation anchor must be stable"
        }
        require(segments[STABLE_PREFIX_SIZE].stability == PromptStability.APPEND_ONLY) {
            "History must be append-only"
        }
        require(segments.drop(STABLE_PREFIX_SIZE + 1).all { it.stability == PromptStability.DYNAMIC }) {
            "Current Bio, relationship, RAG, temporal, and user segments must be dynamic"
        }
        require(cacheIdentity.isNotBlank()) { "cacheIdentity must not be blank" }
    }

    fun appendDynamic(kind: PromptSegmentKind, suffix: String): BuiltPrompt {
        require(kind in DYNAMIC_KINDS) { "Only current dynamic segments may be amended" }
        return copy(
            segments = segments.map { segment ->
                if (segment.kind == kind) segment.appendDynamic(suffix) else segment
            },
        )
    }

    fun wireMessages(): List<PromptMessage> = buildList {
        segments.forEach { segment ->
            if (segment.kind == PromptSegmentKind.HISTORY) {
                segment.wireItems.forEach { item ->
                    add(PromptMessage(item.role, item.text, segment.kind))
                }
            } else if (segment.text.isNotBlank()) {
                add(PromptMessage(segment.role, segment.text, segment.kind))
            }
        }
    }

    fun textPreview(): String = wireMessages()
        .map(PromptMessage::content)
        .filter(String::isNotBlank)
        .joinToString("\n\n")

    internal fun cachePrefixSegments(): List<PromptSegment> = segments.take(CACHE_PREFIX_SIZE)

    companion object {
        val REQUIRED_ORDER = PromptSegmentKind.entries.toList()
        private const val STABLE_PREFIX_SIZE = 3
        private const val CACHE_PREFIX_SIZE = STABLE_PREFIX_SIZE + 1
        private val DYNAMIC_KINDS = setOf(
            PromptSegmentKind.BIO,
            PromptSegmentKind.RELATIONSHIP,
            PromptSegmentKind.RAG,
            PromptSegmentKind.TEMPORAL,
            PromptSegmentKind.USER,
        )

        fun create(segments: List<PromptSegment>, cacheEpoch: Long = 0L): BuiltPrompt {
            require(cacheEpoch >= 0L) { "cacheEpoch must not be negative" }
            val cachePrefix = segments.take(CACHE_PREFIX_SIZE)
            val cacheIdentity = Sha256.hex(
                buildString {
                    cachePrefix.forEach { segment ->
                        append(segment.id.length).append(':').append(segment.id)
                        if (segment.kind == PromptSegmentKind.HISTORY) {
                            append("epoch:").append(cacheEpoch)
                        } else {
                            append(segment.fingerprint.length).append(':').append(segment.fingerprint)
                        }
                    }
                }.encodeToByteArray(),
            )
            return BuiltPrompt(segments = segments, cacheIdentity = cacheIdentity)
        }
    }
}
