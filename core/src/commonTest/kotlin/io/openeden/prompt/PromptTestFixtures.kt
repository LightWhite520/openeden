package io.openeden.prompt

import io.openeden.transcript.PromptHistorySnapshot

internal fun testBuiltPrompt(
    vararg content: Pair<PromptSegmentKind, String>,
    promptHistory: PromptHistorySnapshot = PromptHistorySnapshot(),
    conversationCacheIdentity: ConversationCacheIdentity =
        ConversationCacheIdentity.fromAuthoritativeSessionId("TEST:test-conversation"),
): BuiltPrompt {
    val textByKind = content.toMap()
    require(textByKind.size == content.size) { "Prompt fixture contains duplicate segment kinds" }
    return BuiltPrompt.create(
        PromptSegmentKind.entries.map { kind ->
            if (kind == PromptSegmentKind.HISTORY) {
                PromptSegment.history(promptHistory)
            } else {
                PromptSegment.text(
                    id = kind.name.lowercase(),
                    role = when (kind) {
                        PromptSegmentKind.SYSTEM_CONTRACT -> PromptRole.SYSTEM
                        PromptSegmentKind.USER -> PromptRole.USER
                        else -> PromptRole.DEVELOPER
                    },
                    kind = kind,
                    stability = when (kind) {
                        PromptSegmentKind.SYSTEM_CONTRACT,
                        PromptSegmentKind.PERSONA,
                        PromptSegmentKind.INCARNATION_ANCHOR,
                        -> PromptStability.STABLE
                        else -> PromptStability.DYNAMIC
                    },
                    text = textByKind[kind].orEmpty(),
                )
            }
        },
        conversationCacheIdentity = conversationCacheIdentity,
        cacheEpoch = promptHistory.cacheEpoch,
    )
}
