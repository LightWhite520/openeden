package io.openeden.prompt

import io.openeden.hash.Sha256
import io.openeden.transcript.PromptHistorySnapshot

data class PromptSegment(
    val id: String,
    val role: PromptRole,
    val kind: PromptSegmentKind,
    val stability: PromptStability,
    val text: String,
    val fingerprint: String,
    val turnIds: List<String> = emptyList(),
    val wireItems: List<PromptWireItem> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        require(turnIds.none(String::isBlank)) { "turnIds must not contain blanks" }
        require(kind == PromptSegmentKind.HISTORY || wireItems.isEmpty()) {
            "Only HISTORY may contain wire items"
        }
        require(kind != PromptSegmentKind.HISTORY || text.isEmpty()) {
            "HISTORY wire items are authoritative; flattened history text is forbidden"
        }
    }

    internal fun appendDynamic(suffix: String): PromptSegment {
        require(stability == PromptStability.DYNAMIC) { "Only dynamic segments may be amended per request" }
        val amended = if (text.isBlank()) suffix else "$text\n\n$suffix"
        return copy(text = amended, fingerprint = fingerprint(amended))
    }

    companion object {
        fun text(
            id: String,
            role: PromptRole,
            kind: PromptSegmentKind,
            stability: PromptStability,
            text: String,
        ): PromptSegment = PromptSegment(
            id = id,
            role = role,
            kind = kind,
            stability = stability,
            text = text,
            fingerprint = fingerprint(text),
        )

        fun history(snapshot: PromptHistorySnapshot): PromptSegment {
            val items = buildList {
                snapshot.summary?.let { summary ->
                    add(
                        PromptWireItem(
                            role = PromptRole.DEVELOPER,
                            text = summary.text,
                            turnIds = summary.sourceTurnIds.sorted(),
                            fingerprint = summary.fingerprint,
                        ),
                    )
                }
                snapshot.flattenItems().forEach { item ->
                    add(
                        PromptWireItem(
                            role = PromptRole.fromApiValue(item.role),
                            text = item.text,
                            turnIds = listOf(item.turnId),
                            fingerprint = item.fingerprint,
                        ),
                    )
                }
            }
            val fingerprint = fingerprint(
                buildString {
                    append(snapshot.cacheEpoch).append(':')
                    items.forEach { append(it.fingerprint).append(':') }
                },
            )
            return PromptSegment(
                id = "history",
                role = PromptRole.DEVELOPER,
                kind = PromptSegmentKind.HISTORY,
                stability = PromptStability.APPEND_ONLY,
                text = "",
                fingerprint = fingerprint,
                turnIds = snapshot.sourceTurnIds.sorted(),
                wireItems = items,
            )
        }

        internal fun fingerprint(text: String): String = Sha256.hex(text.encodeToByteArray())
    }
}
