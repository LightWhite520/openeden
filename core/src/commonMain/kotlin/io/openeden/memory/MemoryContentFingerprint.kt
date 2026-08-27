package io.openeden.memory

import io.openeden.hash.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryContentFingerprint {
    const val NORMALIZATION_VERSION: Int = 1
    const val ALGORITHM: String = "SHA-256"

    suspend fun of(content: String): String = withContext(Dispatchers.Default) {
        "v$NORMALIZATION_VERSION:${ALGORITHM.lowercase()}:${Sha256.hex(normalize(content).encodeToByteArray())}"
    }

    fun normalize(content: String): String = normalizeToNfc(
        content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { line -> line.trimEnd() }
            .trim(),
    )
}
