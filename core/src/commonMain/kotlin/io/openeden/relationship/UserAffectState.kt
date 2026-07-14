package io.openeden.relationship

import io.openeden.runtime.affect.EmotionSignal

private fun finiteUnit(value: Float, name: String): Float {
    require(value.isFinite()) { "$name must be finite" }
    return value.coerceIn(0.0f, 1.0f)
}

data class UserAffectState(
    val valence: Float,
    val arousal: Float,
    val dominance: Float,
    val connectionNeed: Float,
    val openness: Float,
    val confidence: Float,
) {
    init {
        finiteUnit(valence, "valence")
        finiteUnit(arousal, "arousal")
        finiteUnit(dominance, "dominance")
        finiteUnit(connectionNeed, "connectionNeed")
        finiteUnit(openness, "openness")
        finiteUnit(confidence, "confidence")
    }

    fun semanticLevel(value: Float): SemanticLevel = when {
        confidence < 0.5f -> SemanticLevel.UNKNOWN
        value < 0.3f -> SemanticLevel.LOW
        value > 0.6f -> SemanticLevel.HIGH
        else -> SemanticLevel.MEDIUM
    }

    fun toEmotionSignal(mapper: UserAffectInfluenceMapper = UserAffectInfluenceMapper.Neutral): EmotionSignal =
        mapper.map(this)

    companion object {
        val Uncertain = UserAffectState(
            valence = 0.5f,
            arousal = 0.5f,
            dominance = 0.5f,
            connectionNeed = 0.5f,
            openness = 0.5f,
            confidence = 0.0f,
        )
    }
}
