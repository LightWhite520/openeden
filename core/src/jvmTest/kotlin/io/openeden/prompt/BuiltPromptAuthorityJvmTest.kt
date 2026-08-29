package io.openeden.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class BuiltPromptAuthorityJvmTest {
    @Test
    fun `create snapshots caller collections and derives stable fingerprints from content`() {
        val historyTurnIds = mutableListOf("turn-1")
        val historyItems = mutableListOf(
            PromptWireItem(
                role = PromptRole.USER,
                text = "original history",
                turnIds = historyTurnIds,
                fingerprint = "caller-supplied-wire-fingerprint",
            ),
        )
        val firstSegments = mutableSegments("original system", historyItems)
        val secondSegments = mutableSegments("different system", historyItems.toMutableList())
        val conversation = ConversationCacheIdentity.fromAuthoritativeSessionId("CLI:local")

        val first = BuiltPrompt.create(firstSegments, conversation, cacheEpoch = 7L)
        val second = BuiltPrompt.create(secondSegments, conversation, cacheEpoch = 7L)

        assertNotEquals(first.cacheIdentity, second.cacheIdentity)
        firstSegments[0] = PromptSegment.text(
            id = "system_contract",
            role = PromptRole.SYSTEM,
            kind = PromptSegmentKind.SYSTEM_CONTRACT,
            stability = PromptStability.STABLE,
            text = "mutated system",
        )
        historyItems.clear()
        historyTurnIds += "turn-2"

        assertEquals("original system", first.wireMessages().first().content)
        assertEquals("original history", first.wireMessages()[3].content)
        assertEquals(listOf("turn-1"), first.segments[3].wireItems.single().turnIds)
        assertFailsWith<UnsupportedOperationException> {
            (first.segments as MutableList<PromptSegment>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (first.segments[3].wireItems as MutableList<PromptWireItem>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (first.segments[3].wireItems.single().turnIds as MutableList<String>).clear()
        }
        val amended = first.appendDynamic(PromptSegmentKind.USER, " amended")
        assertFailsWith<UnsupportedOperationException> {
            (amended.segments as MutableList<PromptSegment>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (BuiltPrompt.REQUIRED_ORDER as MutableList<PromptSegmentKind>).clear()
        }
    }

    @Test
    fun `public prompt constructors retain their JVM descriptors`() {
        val defaultConstructorMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        assertEquals(
            listOf(List::class.java, String::class.java, String::class.java),
            BuiltPrompt::class.java.getDeclaredConstructor(
                List::class.java,
                String::class.java,
                String::class.java,
            ).parameterTypes.toList(),
        )
        assertEquals(
            4,
            BuiltPrompt::class.java.getConstructor(
                List::class.java,
                String::class.java,
                String::class.java,
                defaultConstructorMarker,
            ).parameterCount,
        )
        assertEquals(
            8,
            PromptSegment::class.java.getConstructor(
                String::class.java,
                PromptRole::class.java,
                PromptSegmentKind::class.java,
                PromptStability::class.java,
                String::class.java,
                String::class.java,
                List::class.java,
                List::class.java,
            ).parameterCount,
        )
        assertEquals(
            4,
            PromptWireItem::class.java.getConstructor(
                PromptRole::class.java,
                String::class.java,
                List::class.java,
                String::class.java,
            ).parameterCount,
        )
    }

    private fun mutableSegments(
        systemText: String,
        historyItems: MutableList<PromptWireItem>,
    ): MutableList<PromptSegment> = mutableListOf(
        rawTextSegment(
            id = "system_contract",
            role = PromptRole.SYSTEM,
            kind = PromptSegmentKind.SYSTEM_CONTRACT,
            stability = PromptStability.STABLE,
            text = systemText,
        ),
        rawTextSegment("persona", PromptRole.DEVELOPER, PromptSegmentKind.PERSONA, PromptStability.STABLE, "persona"),
        rawTextSegment(
            "incarnation_anchor",
            PromptRole.DEVELOPER,
            PromptSegmentKind.INCARNATION_ANCHOR,
            PromptStability.STABLE,
            "incarnation",
        ),
        PromptSegment(
            id = "history",
            role = PromptRole.DEVELOPER,
            kind = PromptSegmentKind.HISTORY,
            stability = PromptStability.APPEND_ONLY,
            text = "",
            fingerprint = "caller-supplied-history-fingerprint",
            turnIds = mutableListOf("turn-1"),
            wireItems = historyItems,
        ),
        rawTextSegment("bio", PromptRole.DEVELOPER, PromptSegmentKind.BIO, PromptStability.DYNAMIC, "bio"),
        rawTextSegment(
            "relationship",
            PromptRole.DEVELOPER,
            PromptSegmentKind.RELATIONSHIP,
            PromptStability.DYNAMIC,
            "relationship",
        ),
        rawTextSegment("rag", PromptRole.DEVELOPER, PromptSegmentKind.RAG, PromptStability.DYNAMIC, "rag"),
        rawTextSegment("temporal", PromptRole.DEVELOPER, PromptSegmentKind.TEMPORAL, PromptStability.DYNAMIC, "temporal"),
        rawTextSegment("user", PromptRole.USER, PromptSegmentKind.USER, PromptStability.DYNAMIC, "user"),
    )

    private fun rawTextSegment(
        id: String,
        role: PromptRole,
        kind: PromptSegmentKind,
        stability: PromptStability,
        text: String,
    ) = PromptSegment(
        id = id,
        role = role,
        kind = kind,
        stability = stability,
        text = text,
        fingerprint = "caller-supplied-segment-fingerprint",
    )
}
