package io.openeden.server.bootstrap

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VectorDatabaseConfigTest {
    @Test
    fun `missing vector database settings use safe defaults`() {
        val config = loadVectorDatabaseConfig(MapApplicationConfig())

        assertEquals(true, config.enabled)
        assertEquals("http://localhost:6333", config.url)
        assertEquals(null, config.apiKey)
        assertEquals("openeden_memory", config.collectionPrefix)
        assertEquals("local-v1", config.modelId)
        assertEquals(2_000L, config.requestTimeoutMs)
        assertEquals(30L, config.syncIntervalSeconds)
        assertEquals(30_000L, config.syncIntervalMs)
        assertEquals(128, config.syncBatchSize)
        assertEquals(3, config.failureThreshold)
    }

    @Test
    fun `configured vector database settings are loaded`() {
        val config = loadVectorDatabaseConfig(
            MapApplicationConfig(
                "openeden.vectorDatabase.enabled" to "false",
                "openeden.vectorDatabase.url" to "https://qdrant.example/v1",
                "openeden.vectorDatabase.apiKey" to "secret",
                "openeden.vectorDatabase.collectionPrefix" to "eden_memory",
                "openeden.vectorDatabase.modelId" to "embedding-v2",
                "openeden.vectorDatabase.requestTimeoutMs" to "4000",
                "openeden.vectorDatabase.syncIntervalSeconds" to "45",
                "openeden.vectorDatabase.syncBatchSize" to "64",
                "openeden.vectorDatabase.failureThreshold" to "5",
            ),
        )

        assertEquals(false, config.enabled)
        assertEquals("https://qdrant.example/v1", config.url)
        assertEquals("secret", config.apiKey)
        assertEquals("eden_memory", config.collectionPrefix)
        assertEquals("embedding-v2", config.modelId)
        assertEquals(4_000L, config.requestTimeoutMs)
        assertEquals(45L, config.syncIntervalSeconds)
        assertEquals(45_000L, config.syncIntervalMs)
        assertEquals(64, config.syncBatchSize)
        assertEquals(5, config.failureThreshold)
    }

    @Test
    fun `url must use http or https and must not contain user info`() {
        listOf(
            "ftp://qdrant.example",
            "qdrant.example:6333",
            "http://user:password@qdrant.example:6333",
            "http:///missing-host",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                loadVectorDatabaseConfig(MapApplicationConfig("openeden.vectorDatabase.url" to value))
            }
        }
    }

    @Test
    fun `blank identifiers and invalid numeric values are rejected`() {
        listOf(
            "openeden.vectorDatabase.collectionPrefix" to " ",
            "openeden.vectorDatabase.modelId" to "",
            "openeden.vectorDatabase.requestTimeoutMs" to "0",
            "openeden.vectorDatabase.syncIntervalSeconds" to "-1",
            "openeden.vectorDatabase.syncBatchSize" to "10001",
            "openeden.vectorDatabase.failureThreshold" to "0",
        ).forEach { (path, value) ->
            assertFailsWith<IllegalArgumentException> {
                loadVectorDatabaseConfig(MapApplicationConfig(path to value))
            }
        }
    }
}
