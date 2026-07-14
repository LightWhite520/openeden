package io.openeden.relationship

import io.thymos.AffectState
import io.thymos.ThymosAffectAnalyzer
import java.nio.file.Path

class DjlTextAffectAnalyzer(
    private val delegate: ThymosAffectAnalyzer,
) : UserAffectAnalyzer, AutoCloseable {
    override suspend fun analyze(text: String): UserAffectState = delegate.analyze(text).toUserAffectState()

    override fun close() = delegate.close()

    companion object {
        fun fromQwenBundle(bundlePath: Path, engineName: String = "PyTorch"): DjlTextAffectAnalyzer {
            val fallback = DeterministicUserAffectAnalyzer()
            return DjlTextAffectAnalyzer(
                ThymosAffectAnalyzer.fromBundle(bundlePath, engineName) { text ->
                    fallback.analyze(text).toThymosAffectState()
                },
            )
        }
    }
}

private fun AffectState.toUserAffectState() = UserAffectState(
    valence = valence,
    arousal = arousal,
    dominance = dominance,
    connectionNeed = connectionNeed,
    openness = openness,
    confidence = confidence,
)

private fun UserAffectState.toThymosAffectState() = AffectState(
    valence = valence,
    arousal = arousal,
    dominance = dominance,
    connectionNeed = connectionNeed,
    openness = openness,
    confidence = confidence,
)
