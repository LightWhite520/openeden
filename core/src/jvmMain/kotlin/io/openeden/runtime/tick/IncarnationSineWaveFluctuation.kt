package io.openeden.runtime.tick

import java.security.MessageDigest
import java.util.Random

object IncarnationSineWaveFluctuation {
    fun profile(incarnationId: String): SineWaveFluctuationProfile {
        require(incarnationId.isNotBlank()) { "Incarnation ID must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256").digest(incarnationId.encodeToByteArray())
        var seed = 0L
        repeat(Long.SIZE_BYTES) { index ->
            seed = (seed shl Byte.SIZE_BITS) or (digest[index].toLong() and 0xffL)
        }
        return SecureRandomSineWaveFluctuation.profile(Random(seed))
    }
}
