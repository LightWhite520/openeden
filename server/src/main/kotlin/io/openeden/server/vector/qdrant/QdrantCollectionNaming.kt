package io.openeden.server.vector.qdrant

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Builds a stable physical collection name for one embedding model. */
class QdrantCollectionNaming(private val prefix: String) {
    init {
        require(prefix.isNotBlank()) { "collection prefix must not be blank" }
    }

    fun collectionName(modelId: String): String {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        val safePrefix = sanitize(prefix)
        val safeModel = sanitize(modelId)
        return "${safePrefix}_${safeModel}_${shortSha256(modelId)}"
    }

    operator fun invoke(modelId: String): String = collectionName(modelId)

    private fun sanitize(value: String): String = value
        .map { character ->
            when {
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_' || character == '-' -> character
                else -> '_'
            }
        }
        .joinToString("")
        .trim('_')
        .ifBlank { "collection" }

    private fun shortSha256(value: String): String = sha256(value).toHex().take(HASH_LENGTH)

    companion object {
        private const val HASH_LENGTH = 12

        fun forModel(prefix: String, modelId: String): String = QdrantCollectionNaming(prefix).collectionName(modelId)

        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

        private fun ByteArray.toHex(): String = buildString(size * 2) {
            for (byte in this@toHex) append("%02x".format(byte))
        }
    }
}

object QdrantPointIds {
    fun fromMemoryId(memoryId: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(memoryId.toByteArray(StandardCharsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x40).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(hash)
        return UUID(bytes.long, bytes.long).toString()
    }
}
