package io.openeden.server

import io.openeden.server.db.createdAtMsFromId
import kotlin.test.Test
import kotlin.test.assertEquals

class IdTimestampTest {
    @Test
    fun `production suffix parser handles same millisecond and malformed ids deterministically`() {
        assertEquals(1700000000123L, createdAtMsFromId("QQ:42:1700000000123:raw"))
        assertEquals(1700000000123L, createdAtMsFromId("QQ:42:1700000000123:narrative"))
        assertEquals(0L, createdAtMsFromId("QQ:42:not-a-time:raw"))
        assertEquals(0L, createdAtMsFromId("malformed"))
    }
}
