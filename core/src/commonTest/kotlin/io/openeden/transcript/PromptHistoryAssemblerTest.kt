package io.openeden.transcript

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PromptHistoryAssemblerTest {
    private val serializer = PromptHistorySerializer(
        serializerVersion = 1,
        tokenEstimator = { it.encodeToByteArray().size },
    )
    private val assembler = PromptHistoryAssembler(
        serializer = serializer,
        turnCeiling = 2,
        minimumMutableTailTurns = 4,
    )

    @Test
    fun `serialization is exact and boundaries are deterministic`() = runTest {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            serializer.fingerprint("abc"),
        )
        val turns = (1..6).map(::turn)

        val first = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = turns,
            requiredTailTurns = 2,
            tokenBudget = 100_000,
        )
        val repeated = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = turns,
            requiredTailTurns = 2,
            tokenBudget = 100_000,
        )

        val chunk = first.stableChunks.single()
        assertEquals(
            """[OPENEDEN_PROMPT_HISTORY v1]
session_id=QQ:alpha
cache_epoch=0
turn_count=2
[TURN]
turn_id=turn-1
incarnation_id=incarnation-a
platform=QQ
scope_id=alpha
user_id=user-1
user_text=user-1\\nline\\rcolumn\\ttab\\\=equals
assistant_text=assistant-1
completed_at_ms=1
[/TURN]
[TURN]
turn_id=turn-2
incarnation_id=incarnation-a
platform=QQ
scope_id=alpha
user_id=user-1
user_text=user-2
assistant_text=assistant-2
completed_at_ms=2
[/TURN]
[/OPENEDEN_PROMPT_HISTORY]""",
            chunk.serializedText,
        )
        assertEquals(
            "turn-1" to "turn-2",
            chunk.firstTurnId to chunk.lastTurnId,
        )
        assertEquals(listOf("turn-1", "turn-2"), chunk.turnIds)
        assertEquals(1, chunk.serializerVersion)
        assertEquals(
            "d2eb4fbebc01ca12b8dec5c1fbf5ac37568e5831e6df43d4c242704cd17d5670",
            chunk.fingerprint,
        )
        assertEquals(first, repeated)
        assertEquals((3..6).map { "turn-$it" }, first.mutableTail.map { it.turnId })
        assertEquals((1..6).map { "turn-$it" }.toSet(), first.sourceTurnIds)

        val extended = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = turns + turn(7),
            requiredTailTurns = 2,
            tokenBudget = 100_000,
            existingStableChunks = first.stableChunks,
            cacheEpoch = first.cacheEpoch,
            storedSerializerVersion = serializer.serializerVersion,
        )
        assertEquals(first.stableChunks, extended.stableChunks)
        assertEquals((3..7).map { "turn-$it" }, extended.mutableTail.map { it.turnId })
        assertEquals((1..7).map { "turn-$it" }.toSet(), extended.sourceTurnIds)

        val sealedAgain = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = turns + (7..8).map(::turn),
            requiredTailTurns = 2,
            tokenBudget = 100_000,
            existingStableChunks = extended.stableChunks,
            cacheEpoch = extended.cacheEpoch,
            storedSerializerVersion = serializer.serializerVersion,
        )
        assertEquals(listOf("turn-1", "turn-2"), sealedAgain.stableChunks.first().turnIds)
        assertEquals(listOf("turn-3", "turn-4"), sealedAgain.stableChunks[1].turnIds)
        assertEquals((5..8).map { "turn-$it" }, sealedAgain.mutableTail.map { it.turnId })
        assertEquals(chunk.serializedText, sealedAgain.stableChunks.first().serializedText)
        assertEquals(chunk.fingerprint, sealedAgain.stableChunks.first().fingerprint)
        assertNotEquals(chunk.fingerprint, sealedAgain.stableChunks[1].fingerprint)
    }

    @Test
    fun `serializer changes start a new epoch without reusing old chunks`() = runTest {
        val initial = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = (1..6).map(::turn),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
        )
        val changed = PromptHistoryAssembler(
            serializer = PromptHistorySerializer(
                serializerVersion = 2,
                tokenEstimator = { it.encodeToByteArray().size },
            ),
            turnCeiling = 2,
            minimumMutableTailTurns = 4,
        ).assemble(
            sessionId = "QQ:alpha",
            turns = (1..6).map(::turn),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
            existingStableChunks = initial.stableChunks,
            cacheEpoch = initial.cacheEpoch,
            storedSerializerVersion = initial.stableChunks.first().serializerVersion,
        )

        assertEquals(1, changed.cacheEpoch)
        assertEquals(1, changed.stableChunks.size)
        assertEquals(1, changed.stableChunks.single().cacheEpoch)
        assertEquals(2, changed.stableChunks.single().serializerVersion)
        assertNotEquals(initial.stableChunks.single().fingerprint, changed.stableChunks.single().fingerprint)
        assertEquals((3..6).map { "turn-$it" }, changed.mutableTail.map { it.turnId })
    }

    private fun turn(index: Int): ConversationTurn = ConversationTurn(
        turnId = "turn-$index",
        incarnationId = "incarnation-a",
        sessionId = "QQ:alpha",
        platform = "QQ",
        scopeId = "alpha",
        userId = "user-1",
        userText = if (index == 1) "user-1\\nline\\rcolumn\\ttab\\=equals" else "user-$index",
        assistantText = "assistant-$index",
        completedAtMs = index.toLong(),
    )
}
