package io.openeden.cli.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedCommittedIdsTest {
    @Test
    fun `bounded ownership evicts least recently used id`() {
        val committed = BoundedCommittedIds(capacity = 2)

        assertTrue(committed.mark("oldest"))
        assertTrue(committed.mark("evicted"))
        assertFalse(committed.mark("oldest"))
        assertTrue(committed.mark("newest"))

        assertFalse(committed.mark("oldest"))
        assertTrue(committed.mark("evicted"))
    }
}
