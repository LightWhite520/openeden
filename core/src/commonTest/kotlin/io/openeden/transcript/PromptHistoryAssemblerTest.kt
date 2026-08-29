package io.openeden.transcript

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `tail to sealed rollover preserves flattened wire items`() = runTest {
        val rolloverAssembler = PromptHistoryAssembler(
            serializer = serializer,
            turnCeiling = 1,
            minimumMutableTailTurns = 4,
        )
        val turns = (1..5).map(::turn)

        val before = rolloverAssembler.assemble("QQ:alpha", turns.take(4), 2, 100_000)
        val after = rolloverAssembler.assemble(
            sessionId = "QQ:alpha",
            turns = turns,
            requiredTailTurns = 2,
            tokenBudget = 100_000,
            existingStableChunks = before.stableChunks,
            cacheEpoch = before.cacheEpoch,
        )

        assertTrue(before.stableChunks.isEmpty())
        assertEquals(listOf("turn-1"), after.stableChunks.single().turnIds)
        assertEquals(before.cacheEpoch, after.cacheEpoch)
        assertEquals(
            before.flattenItems(),
            after.flattenItems().take(before.flattenItems().size),
        )
    }

    @Test
    fun `compaction preserves commitments and starts one new epoch`() = runTest {
        var generations = 0
        val compactor = PromptHistoryCompactor.validated { request ->
            generations += 1
            assertEquals(1, request.schemaVersion)
            """
            {
              "schema_version": 1,
              "named_entities": ["小林"],
              "commitments": ["约定周五继续完成模型评审"],
              "unresolved_questions": ["是否需要补充回滚演练"],
              "relationship_facts": ["小林负责最终验收"],
              "chronology": ["先确认范围", "随后完成实现"]
            }
            """.trimIndent()
        }
        val source = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = (1..6).map(::turn),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
        )

        val compacted = compactor.compact("compact-1", source)

        assertTrue(assertNotNull(compacted.summary).text.contains("约定周五继续完成模型评审"))
        assertEquals(source.cacheEpoch + 1L, compacted.cacheEpoch)
        assertTrue(compacted.stableChunks.isEmpty())
        assertEquals(source.sourceTurnIds, compacted.sourceTurnIds)
        assertEquals(compacted, compactor.compact("compact-1", source))
        assertEquals(1, generations)
    }

    @Test
    fun `failed compaction retains the active epoch and snapshot`() = runTest {
        val source = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = (1..6).map(::turn),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
        )
        val failed = PromptHistoryCompactor.validated { error("summary provider unavailable") }
        val invalid = PromptHistoryCompactor.validated {
            """{"schema_version":1,"commitments":["约定仍需保留"]}"""
        }

        assertEquals(source, failed.compact("compact-failed", source))
        assertEquals(source, invalid.compact("compact-invalid", source))
        assertEquals(source.cacheEpoch, failed.compact("compact-failed-again", source).cacheEpoch)
    }

    @Test
    fun `one request id performs at most one compaction attempt`() = runTest {
        var generations = 0
        val source = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = (1..6).map(::turn),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
        )
        val compactor = PromptHistoryCompactor.validated {
            generations += 1
            yield()
            error("summary provider unavailable")
        }

        val results = listOf(
            async { compactor.compact("compact-once", source) },
            async { compactor.compact("compact-once", source) },
        ).awaitAll()

        assertEquals(listOf(source, source), results)
        assertEquals(source, compactor.compact("compact-once", source))
        assertEquals(1, generations)
    }

    @Test
    fun `compaction never wraps the cache epoch`() = runTest {
        val source = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = (1..6).map(::turn),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
            cacheEpoch = Long.MAX_VALUE,
        )
        val compactor = PromptHistoryCompactor.validated {
            """
            {
              "schema_version": 1,
              "named_entities": [],
              "commitments": [],
              "unresolved_questions": [],
              "relationship_facts": [],
              "chronology": ["保留当前历史"]
            }
            """.trimIndent()
        }

        assertEquals(source, compactor.compact("compact-max-epoch", source))
    }

    @Test
    fun `summary source turns are not resealed during later assembly`() = runTest {
        val turns = (1..7).map(::turn)
        val source = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = turns.take(6),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
        )
        val compacted = PromptHistoryCompactor.validated { validSummaryDocument() }
            .compact("compact-summary", source)

        val resumed = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = turns,
            requiredTailTurns = 4,
            tokenBudget = 100_000,
            existingStableChunks = compacted.stableChunks,
            existingSummary = assertNotNull(compacted.summary),
            cacheEpoch = compacted.cacheEpoch,
        )

        assertEquals(compacted.summary, resumed.summary)
        assertTrue(resumed.flattenItems().none { it.turnId in setOf("turn-1", "turn-2") })
        assertEquals((1..7).map { "turn-$it" }.toSet(), resumed.sourceTurnIds)
    }

    @Test
    fun `completed request cache evicts the oldest result at its configured bound`() = runTest {
        var generations = 0
        val source = assembler.assemble(
            sessionId = "QQ:alpha",
            turns = (1..6).map(::turn),
            requiredTailTurns = 4,
            tokenBudget = 100_000,
        )
        val compactor = PromptHistoryCompactor.validated(maxRememberedRequests = 2) {
            generations += 1
            validSummaryDocument()
        }

        repeat(3) { index -> compactor.compact("bounded-$index", source) }
        compactor.compact("bounded-0", source)

        assertEquals(4, generations)
    }

    @Test
    fun `serializer migration rejects max epoch before serializing items`() = runTest {
        var tokenEstimates = 0
        val changedAssembler = PromptHistoryAssembler(
            serializer = PromptHistorySerializer(serializerVersion = 2) {
                tokenEstimates += 1
                1
            },
            turnCeiling = 2,
            minimumMutableTailTurns = 4,
        )

        val failure = runCatching {
            changedAssembler.assemble(
                sessionId = "QQ:alpha",
                turns = (1..6).map(::turn),
                requiredTailTurns = 4,
                tokenBudget = 100_000,
                cacheEpoch = Long.MAX_VALUE,
                storedSerializerVersion = 1,
            )
        }.exceptionOrNull()

        assertIs<IllegalArgumentException>(failure)
        assertEquals(0, tokenEstimates)
    }

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
            listOf(
                Triple("user", "user-1\\nline\\rcolumn\\ttab\\=equals", "turn-1"),
                Triple("assistant", "assistant-1", "turn-1"),
                Triple("user", "user-2", "turn-2"),
                Triple("assistant", "assistant-2", "turn-2"),
            ),
            chunk.items.map { Triple(it.role, it.text, it.turnId) },
        )
        assertTrue(chunk.items.all(serializer::isValid))
        assertEquals(
            "turn-1" to "turn-2",
            chunk.firstTurnId to chunk.lastTurnId,
        )
        assertEquals(listOf("turn-1", "turn-2"), chunk.turnIds)
        assertEquals(1, chunk.serializerVersion)
        assertEquals(chunk.fingerprint, repeated.stableChunks.single().fingerprint)
        assertEquals(first, repeated)
        assertEquals((3..6).map { "turn-$it" }, first.mutableTail.map { it.turnId }.distinct())
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
        assertEquals((3..7).map { "turn-$it" }, extended.mutableTail.map { it.turnId }.distinct())
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
        assertEquals((5..8).map { "turn-$it" }, sealedAgain.mutableTail.map { it.turnId }.distinct())
        assertEquals(chunk.items, sealedAgain.stableChunks.first().items)
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
        assertEquals(initial.stableChunks.single().fingerprint, changed.stableChunks.single().fingerprint)
        assertEquals((3..6).map { "turn-$it" }, changed.mutableTail.map { it.turnId }.distinct())
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

    private fun validSummaryDocument(): String =
        """
        {
          "schema_version": 1,
          "named_entities": ["小林"],
          "commitments": ["约定周五继续完成模型评审"],
          "unresolved_questions": ["是否需要补充回滚演练"],
          "relationship_facts": ["小林负责最终验收"],
          "chronology": ["先确认范围", "随后完成实现"]
        }
        """.trimIndent()
}
