package io.openeden.server.bootstrap

import io.ktor.server.config.ApplicationConfig
import java.net.URI

/** Configuration for the optional Qdrant candidate-index projection. */
data class VectorDatabaseConfig(
    val enabled: Boolean = true,
    val url: String = DEFAULT_URL,
    val apiKey: String? = null,
    val collectionPrefix: String = DEFAULT_COLLECTION_PREFIX,
    val modelId: String = DEFAULT_MODEL_ID,
    val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val syncIntervalSeconds: Long = DEFAULT_SYNC_INTERVAL_SECONDS,
    val syncBatchSize: Int = DEFAULT_SYNC_BATCH_SIZE,
    val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
) {
    val syncIntervalMs: Long get() = syncIntervalSeconds * MILLIS_PER_SECOND

    init {
        requireHttpUrl(url)
        require(collectionPrefix.isNotBlank()) { "vector database collectionPrefix must not be blank" }
        require(modelId.isNotBlank()) { "vector database modelId must not be blank" }
        require(requestTimeoutMs in 1..MAX_TIMEOUT_MS) {
            "vector database requestTimeoutMs must be between 1 and $MAX_TIMEOUT_MS"
        }
        require(syncIntervalSeconds in 1..MAX_SYNC_INTERVAL_SECONDS) {
            "vector database syncIntervalSeconds must be between 1 and $MAX_SYNC_INTERVAL_SECONDS"
        }
        require(syncBatchSize in 1..MAX_SYNC_BATCH_SIZE) {
            "vector database syncBatchSize must be between 1 and $MAX_SYNC_BATCH_SIZE"
        }
        require(failureThreshold in 1..MAX_FAILURE_THRESHOLD) {
            "vector database failureThreshold must be between 1 and $MAX_FAILURE_THRESHOLD"
        }
    }

    companion object {
        const val DEFAULT_URL = "http://localhost:6333"
        const val DEFAULT_COLLECTION_PREFIX = "openeden_memory"
        const val DEFAULT_MODEL_ID = "local-v1"
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val DEFAULT_SYNC_INTERVAL_SECONDS = 30L
        const val DEFAULT_SYNC_BATCH_SIZE = 128
        const val DEFAULT_FAILURE_THRESHOLD = 3

        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_SYNC_INTERVAL_SECONDS = 86_400L
        const val MAX_SYNC_BATCH_SIZE = 10_000
        const val MAX_FAILURE_THRESHOLD = 100
        const val MILLIS_PER_SECOND = 1_000L
    }
}

internal fun loadVectorDatabaseConfig(config: ApplicationConfig): VectorDatabaseConfig {
    fun value(path: String, default: String): String =
        config.propertyOrNull(path)?.getString() ?: default

    fun parseBoolean(path: String, default: Boolean): Boolean {
        val raw = value(path, default.toString())
        return when (raw.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$path must be true or false")
        }
    }

    fun parseLong(path: String, default: Long): Long =
        value(path, default.toString()).trim().toLongOrNull()
            ?: throw IllegalArgumentException("$path must be an integer")

    fun parseInt(path: String, default: Int): Int =
        value(path, default.toString()).trim().toIntOrNull()
            ?: throw IllegalArgumentException("$path must be an integer")

    return VectorDatabaseConfig(
        enabled = parseBoolean("openeden.vectorDatabase.enabled", true),
        url = value("openeden.vectorDatabase.url", VectorDatabaseConfig.DEFAULT_URL).trim(),
        apiKey = value("openeden.vectorDatabase.apiKey", "").trim().ifEmpty { null },
        collectionPrefix = value(
            "openeden.vectorDatabase.collectionPrefix",
            VectorDatabaseConfig.DEFAULT_COLLECTION_PREFIX,
        ).trim(),
        modelId = value("openeden.vectorDatabase.modelId", VectorDatabaseConfig.DEFAULT_MODEL_ID).trim(),
        requestTimeoutMs = parseLong(
            "openeden.vectorDatabase.requestTimeoutMs",
            VectorDatabaseConfig.DEFAULT_TIMEOUT_MS,
        ),
        syncIntervalSeconds = parseLong(
            "openeden.vectorDatabase.syncIntervalSeconds",
            VectorDatabaseConfig.DEFAULT_SYNC_INTERVAL_SECONDS,
        ),
        syncBatchSize = parseInt(
            "openeden.vectorDatabase.syncBatchSize",
            VectorDatabaseConfig.DEFAULT_SYNC_BATCH_SIZE,
        ),
        failureThreshold = parseInt(
            "openeden.vectorDatabase.failureThreshold",
            VectorDatabaseConfig.DEFAULT_FAILURE_THRESHOLD,
        ),
    )
}

private fun requireHttpUrl(value: String) {
    val uri = runCatching { URI(value) }.getOrElse {
        throw IllegalArgumentException("vector database url must be a valid HTTP(S) URL", it)
    }
    require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
        "vector database url must use http or https"
    }
    require(uri.userInfo.isNullOrBlank()) {
        "vector database url must not contain user info"
    }
    require(!uri.host.isNullOrBlank()) {
        "vector database url must include a host"
    }
}
