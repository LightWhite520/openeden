package io.openeden.transcript

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InMemoryTranscriptStoreTest {
    @Test
    fun `append is idempotent and latest page is chronological`() = runTest {
        val store = InMemoryTranscriptStore("incarnation-a", createdAtMs = 123L)

        repeat(55) { index -> store.append(turn(index)) }
        store.append(turn(54))

        assertEquals(ActiveIncarnation("incarnation-a", 123L), store.activeIncarnation())
        val page = store.page(limit = 50)
        assertEquals((5 until 55).map { "turn-$it" }, page.turns.map { it.turnId })
        assertTrue(page.hasMore)
        assertNotNull(page.before)
        assertEquals("turn-5", page.before.turnId)
    }

    @Test
    fun `duplicate turn id with different payload is rejected`() = runTest {
        val store = InMemoryTranscriptStore("incarnation-a", createdAtMs = 123L)
        store.append(turn(1))

        assertFailsWith<IllegalArgumentException> {
            store.append(turn(1).copy(assistantText = "different"))
        }
    }

    @Test
    fun `append rejects a turn from another incarnation`() = runTest {
        val store = InMemoryTranscriptStore("incarnation-a", createdAtMs = 123L)

        assertFailsWith<IllegalArgumentException> {
            store.append(turn(1).copy(incarnationId = "incarnation-b"))
        }
        assertEquals(emptyList(), store.page(limit = 50).turns)
    }

    @Test
    fun `cursor from another incarnation is rejected`() = runTest {
        val store = InMemoryTranscriptStore("incarnation-a", createdAtMs = 123L)

        assertFailsWith<InvalidHistoryCursorException> {
            store.page(
                limit = 10,
                before = HistoryCursor("incarnation-b", completedAtMs = 10L, turnId = "turn-10"),
            )
        }
    }

    @Test
    fun `cursor returns remaining older turns without duplication`() = runTest {
        val store = InMemoryTranscriptStore("incarnation-a", createdAtMs = 123L)
        repeat(55) { index -> store.append(turn(index)) }

        val latest = store.page(limit = 50)
        val older = store.page(limit = 50, before = latest.before)

        assertEquals((0 until 5).map { "turn-$it" }, older.turns.map { it.turnId })
        assertEquals(emptyList(), latest.turns.map { it.turnId }.intersect(older.turns.map { it.turnId }.toSet()).toList())
        assertEquals(false, older.hasMore)
        assertEquals(null, older.before)
    }

    @Test
    fun `page clamps limits to supported range`() = runTest {
        val store = InMemoryTranscriptStore("incarnation-a", createdAtMs = 123L)
        repeat(55) { index -> store.append(turn(index)) }

        val minimum = store.page(limit = 0)
        val maximum = store.page(limit = 100)

        assertEquals(listOf("turn-54"), minimum.turns.map { it.turnId })
        assertTrue(minimum.hasMore)
        assertEquals(50, maximum.turns.size)
        assertEquals((5 until 55).map { "turn-$it" }, maximum.turns.map { it.turnId })
    }

    private fun turn(index: Int): ConversationTurn = ConversationTurn(
        turnId = "turn-$index",
        incarnationId = "incarnation-a",
        sessionId = "QQ:group-1",
        platform = "QQ",
        scopeId = "group-1",
        userId = "user-1",
        userText = "user-$index",
        assistantText = "assistant-$index",
        completedAtMs = index.toLong(),
    )
}
