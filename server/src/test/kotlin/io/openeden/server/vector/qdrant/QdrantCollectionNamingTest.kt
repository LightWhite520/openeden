package io.openeden.server.vector.qdrant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QdrantCollectionNamingTest {
    @Test
    fun `model aware names are deterministic ascii and preserve collisions with a hash`() {
        val naming = QdrantCollectionNaming("openeden memory")

        val first = naming.collectionName("text/encoder:v1")
        val same = naming.collectionName("text/encoder:v1")
        val other = naming.collectionName("text_encoder:v1")

        assertEquals(first, same)
        assertNotEquals(first, other)
        assertTrue(first.startsWith("openeden_memory_text_encoder_v1_"))
        assertTrue(first.all { it.code < 128 })
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `memory ids map to deterministic RFC UUID point ids`() {
        val first = QdrantPointIds.fromMemoryId("QQ:42:1000:raw")
        val same = QdrantPointIds.fromMemoryId("QQ:42:1000:raw")
        val other = QdrantPointIds.fromMemoryId("QQ:42:1001:raw")

        assertEquals(first, same)
        assertNotEquals(first, other)
        assertTrue(first.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }
}
