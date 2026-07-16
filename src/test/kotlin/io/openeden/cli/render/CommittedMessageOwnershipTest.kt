package io.openeden.cli.render

import kotlin.test.Test
import kotlin.test.assertEquals

class CommittedMessageOwnershipTest {
    @Test
    fun `visible ids stay stable beyond tombstone capacity`() {
        val ownership = CommittedMessageOwnership(tombstoneCapacity = 2)

        assertEquals(listOf("a", "b", "c"), ownership.newIds(listOf("a", "b", "c")))
        assertEquals(emptyList(), ownership.newIds(listOf("a", "b", "c")))
    }

    @Test
    fun `bounded tombstones evict oldest removed id`() {
        val ownership = CommittedMessageOwnership(tombstoneCapacity = 2)
        ownership.newIds(listOf("a", "b", "c"))

        assertEquals(emptyList(), ownership.newIds(emptyList()))
        assertEquals(listOf("a"), ownership.newIds(listOf("a", "b", "c")))
        assertEquals(emptyList(), ownership.newIds(listOf("a", "b", "c")))
    }

    @Test
    fun `removed id rehydrates without becoming new inside tombstone capacity`() {
        val ownership = CommittedMessageOwnership(tombstoneCapacity = 2)
        assertEquals(listOf("same"), ownership.newIds(listOf("same")))

        ownership.newIds(emptyList())

        assertEquals(emptyList(), ownership.newIds(listOf("same")))
    }
}
