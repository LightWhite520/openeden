package io.openeden.transcript

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class PromptHistorySourceValidatorTest {
    private val items = listOf("invitation", "confession", "cooking").map {
        PromptHistoryItem("user", "source", it, "fingerprint-$it")
    }

    @Test
    fun `canonical chronology retains omitted facts verbatim and orders by evidence`() {
        val result = PromptHistorySourceValidator.canonicalChronology(items, emptySet(),
            listOf("[invitation] Unconfirmed invitation", "[confession] Attributed statement"),
            listOf("[cooking] Confirmed", "[invitation] Unconfirmed invitation"))
        assertEquals(listOf("[invitation] Unconfirmed invitation", "[confession] Attributed statement", "[cooking] Confirmed"), result)
    }

    @Test
    fun `unknown evidence keeps original epoch and history without retrying generation`() = runTest {
        val source = PromptHistorySnapshot(stableChunks = listOf(PromptHistoryChunk("scope", 3, items, 10, 1)),
            sourceTurnIds = items.map { it.turnId }.toSet(), cacheEpoch = 3)
        var calls = 0
        val compactor = PromptHistoryCompactor.validated(requireSourceAnchors = true) {
            calls++
            """{"schema_version":1,"named_entities":[],"commitments":[],"unresolved_questions":[],"relationship_facts":[],"chronology":["[invented] Event"]}"""
        }
        assertSame(source, compactor.compact("request", source))
        assertSame(source, compactor.compact("request", source))
        assertEquals(1, calls)
    }

    @Test
    fun `accepts attributed facts and unresolved invitations in source order`() {
        PromptHistorySourceValidator.validate(items, emptySet(), listOf("[invitation] Not confirmed"),
            listOf("[confession] User described an earlier exchange"),
            listOf("[invitation] Proposed walk", "[confession] User description", "[cooking] Cooking confirmed"))
    }

    @Test
    fun `rejects invented sources missing chronology links and reordered proposals`() {
        for (chronology in listOf(
            listOf("[invented] Event"),
            listOf("[cooking] Confirmed"),
            listOf("[cooking] Confirmed", "[invitation] Not confirmed"),
            listOf("No source anchor"),
        )) {
            assertFailsWith<IllegalArgumentException> {
                PromptHistorySourceValidator.validate(items, emptySet(), listOf("[invitation] Not confirmed"),
                    emptyList(), chronology)
            }
        }
    }
}
