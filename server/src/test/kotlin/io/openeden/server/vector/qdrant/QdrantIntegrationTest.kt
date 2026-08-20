package io.openeden.server.vector.qdrant

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.memory.VectorSearchRequest
import io.openeden.server.vector.QdrantCircuitBreaker
import io.openeden.server.vector.ResilientVectorIndex
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in against a caller-provided Qdrant instance; normal test runs never use the network. */
class QdrantIntegrationTest {
    @Test
    fun `isolated named-vector collection filters sessions and falls back safely`() = kotlinx.coroutines.test.runTest {
        val url = System.getenv("OPENEDEN_QDRANT_TEST_URL")?.trim()
        assumeTrue("OPENEDEN_QDRANT_TEST_URL is not set", !url.isNullOrBlank())

        val client = QdrantClient(
            baseUrl = url!!,
            apiKey = System.getenv("OPENEDEN_QDRANT_TEST_API_KEY")?.takeIf { it.isNotBlank() },
            timeoutMillis = 5_000,
        )
        val naming = QdrantCollectionNaming("openeden_test_${UUID.randomUUID().toString().replace("-", "")}")
        val modelId = "integration-v1"
        val collection = naming.collectionName(modelId)
        val index = QdrantVectorIndex(client, naming, modelId)
        val sessionA1 = entry("memory-a-1", "session-a", listOf(1.0f, 0.0f))
        val sessionA2 = entry("memory-a-2", "session-a", listOf(0.9f, 0.1f))
        val sessionB1 = entry("memory-b-1", "session-b", listOf(1.0f, 0.0f))

        try {
            index.insert(sessionA1)
            index.insert(sessionA2)
            index.insert(sessionB1)

            val schema = assertNotNull(client.inspectCollection(collection))
            assertEquals(setOf("semantic", "emotional"), schema.vectors.keys)
            assertEquals(2, schema.vectors.getValue("semantic").size)
            assertEquals(8, schema.vectors.getValue("emotional").size)

            val request = VectorSearchRequest("session-a", listOf(1.0f, 0.0f), limit = 10)
            val filtered = index.search(request)
            assertEquals(setOf("memory-a-1", "memory-a-2"), filtered.map { it.memoryId }.toSet())
            assertTrue(filtered.all { it.entry == null }, "remote Qdrant hits must be candidate IDs")

            val rawHits = client.searchSemanticPoints(
                collection = collection,
                vector = floatArrayOf(1.0f, 0.0f),
                limit = 10,
                filter = QdrantFilter(listOf(QdrantFieldCondition("session_id", "session-a"))),
            )
            assertEquals(
                setOf(QdrantPointIds.fromMemoryId(sessionA1.id), QdrantPointIds.fromMemoryId(sessionA2.id)),
                rawHits.map { it.id }.toSet(),
            )
            assertTrue(rawHits.all { it.payload["memory_id"] in setOf(sessionA1.id, sessionA2.id) })

            val unavailableClient = QdrantClient("http://127.0.0.1:1", timeoutMillis = 250)
            try {
                val resilient = ResilientVectorIndex(
                    primary = QdrantVectorIndex(unavailableClient, naming, modelId),
                    fallback = RebuildableInMemoryVectorIndex(),
                    circuit = QdrantCircuitBreaker(failureThreshold = 1),
                )
                resilient.insert(sessionA1)
                val fallbackHits = resilient.search(request.copy(limit = 1))
                assertEquals(listOf(sessionA1.id), fallbackHits.map { it.memoryId })
                assertNotNull(fallbackHits.single().entry)
                assertTrue(resilient.status().fallbackActive)
            } finally {
                unavailableClient.close()
            }
        } finally {
            runCatching { client.deleteCollection(collection) }
            client.close()
        }
    }

    private fun entry(id: String, sessionId: String, semantic: List<Float>) = MemoryEntry(
        id = id,
        sessionId = sessionId,
        content = id,
        room = MemoryRoom.EVENT_ROOM,
        kind = MemoryKind.RAW,
        semanticEmbedding = semantic,
        emotionalEmbedding = List(8) { index -> if (index == 0) 1.0f else 0.0f },
        metadata = MemoryMetadata(BioVector.Neutral, 0.0f, VectorDelta.Zero, BioVector.Neutral, "integration-user"),
    )
}
