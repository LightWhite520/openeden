package io.openeden.prompt

import io.openeden.hash.Sha256

@ConsistentCopyVisibility
data class BuiltPrompt internal constructor(
    val segments: List<PromptSegment>,
    val cacheIdentity: String,
    val conversationCacheIdentity: ConversationCacheIdentity,
) {
    private var authority: PromptAuthority? = null

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
            segments = immutablePromptList(segments.map { segment ->
                if (segment.kind == kind) segment.appendDynamic(suffix) else segment
            }),
        ).also { amended -> amended.authority = authority }
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

    internal fun authoritativeSnapshot(): BuiltPrompt {
        val currentAuthority = authority
        val snapshotSegments = snapshotSegments(segments, currentAuthority?.historyEpoch)
        val prefixFingerprint = prefixFingerprint(snapshotSegments)
        val trusted = currentAuthority?.takeIf {
            cacheIdentity == it.cacheIdentity && prefixFingerprint == it.prefixFingerprint
        }
        val authoritativeCacheIdentity = trusted?.cacheIdentity
            ?: cacheIdentity(snapshotSegments, historyEpoch = null)
        return BuiltPrompt(
            segments = snapshotSegments,
            cacheIdentity = authoritativeCacheIdentity,
            conversationCacheIdentity = conversationCacheIdentity,
        ).also { snapshot ->
            snapshot.authority = PromptAuthority(
                historyEpoch = trusted?.historyEpoch,
                prefixFingerprint = prefixFingerprint,
                cacheIdentity = authoritativeCacheIdentity,
            )
        }
    }

    companion object {
        val REQUIRED_ORDER = immutablePromptList(PromptSegmentKind.entries)
        private const val STABLE_PREFIX_SIZE = 3
        private const val CACHE_PREFIX_SIZE = STABLE_PREFIX_SIZE + 1
        private val DYNAMIC_KINDS = setOf(
            PromptSegmentKind.BIO,
            PromptSegmentKind.RELATIONSHIP,
            PromptSegmentKind.RAG,
            PromptSegmentKind.TEMPORAL,
            PromptSegmentKind.USER,
        )

        fun create(
            segments: List<PromptSegment>,
            conversationCacheIdentity: ConversationCacheIdentity,
            cacheEpoch: Long = 0L,
        ): BuiltPrompt {
            require(cacheEpoch >= 0L) { "cacheEpoch must not be negative" }
            val snapshotSegments = snapshotSegments(segments, cacheEpoch)
            val cacheIdentity = cacheIdentity(snapshotSegments, cacheEpoch)
            return BuiltPrompt(
                segments = snapshotSegments,
                cacheIdentity = cacheIdentity,
                conversationCacheIdentity = conversationCacheIdentity,
            ).also { prompt ->
                prompt.authority = PromptAuthority(
                    historyEpoch = cacheEpoch,
                    prefixFingerprint = prefixFingerprint(snapshotSegments),
                    cacheIdentity = cacheIdentity,
                )
            }
        }

        private fun snapshotSegments(segments: List<PromptSegment>, historyEpoch: Long?): List<PromptSegment> =
            immutablePromptList(segments.map { segment -> segment.authoritativeSnapshot(historyEpoch) })

        private fun cacheIdentity(segments: List<PromptSegment>, historyEpoch: Long?): String = Sha256.hex(
            buildString {
                segments.take(CACHE_PREFIX_SIZE).forEach { segment ->
                    appendRecord(segment.id)
                    appendRecord(segment.role.apiValue)
                    appendRecord(segment.kind.name)
                    appendRecord(segment.stability.name)
                    if (segment.kind == PromptSegmentKind.HISTORY && historyEpoch != null) {
                        appendRecord("epoch:$historyEpoch")
                    } else {
                        appendRecord(segment.fingerprint)
                    }
                }
            }.encodeToByteArray(),
        )

        private fun prefixFingerprint(segments: List<PromptSegment>): String = Sha256.hex(
            buildString {
                segments.take(CACHE_PREFIX_SIZE).forEach { segment ->
                    appendRecord(segment.id)
                    appendRecord(segment.role.apiValue)
                    appendRecord(segment.kind.name)
                    appendRecord(segment.stability.name)
                    appendRecord(segment.text)
                    appendRecord(segment.fingerprint)
                    segment.turnIds.forEach { turnId -> appendRecord(turnId) }
                    segment.wireItems.forEach { item ->
                        appendRecord(item.role.apiValue)
                        appendRecord(item.text)
                        item.turnIds.forEach { turnId -> appendRecord(turnId) }
                    }
                }
            }.encodeToByteArray(),
        )

        private fun StringBuilder.appendRecord(value: String) {
            append(value.encodeToByteArray().size).append(':').append(value)
        }
    }

    private data class PromptAuthority(
        val historyEpoch: Long?,
        val prefixFingerprint: String,
        val cacheIdentity: String,
    )
}
