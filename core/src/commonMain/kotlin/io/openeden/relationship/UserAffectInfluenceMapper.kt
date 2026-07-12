package io.openeden.relationship

import io.openeden.bio.VectorDelta
import io.openeden.runtime.EmotionSignal

data class UserAffectInfluenceMapper(
    val toL: List<Float>,
    val toP: List<Float>,
    val toE: List<Float>,
    val toS: List<Float>,
    val toTau: List<Float>,
    val toV: List<Float>,
    val toM: List<Float>,
    val toF: List<Float>,
) {
    init {
        listOf(toL, toP, toE, toS, toTau, toV, toM, toF).forEach {
            require(it.size == 5) { "Each affect influence row must have 5 coefficients" }
            require(it.all(Float::isFinite)) { "Influence coefficients must be finite" }
        }
    }

    fun map(state: UserAffectState): EmotionSignal {
        val affect = listOf(
            state.valence,
            state.arousal,
            state.dominance,
            state.connectionNeed,
            state.openness,
        )
        fun dot(row: List<Float>): Float {
            var result = 0.0f
            for (index in row.indices) result += row[index] * (affect[index] - 0.5f)
            return result
        }
        val raw = VectorDelta(
            l = dot(toL), p = dot(toP), e = dot(toE), s = dot(toS),
            tau = dot(toTau), v = dot(toV), m = dot(toM), f = dot(toF),
        )
        return EmotionSignal(delta = raw, confidence = state.confidence)
    }

    companion object {
        val Neutral = UserAffectInfluenceMapper(
            toL = List(5) { 0.0f }, toP = List(5) { 0.0f }, toE = List(5) { 0.0f },
            toS = List(5) { 0.0f }, toTau = List(5) { 0.0f }, toV = List(5) { 0.0f },
            toM = List(5) { 0.0f }, toF = List(5) { 0.0f },
        )

        val Default = UserAffectInfluenceMapper(
            toL = listOf(0.05f, 0.0f, 0.08f, 0.0f, 0.0f),
            toP = listOf(0.30f, 0.12f, 0.0f, 0.05f, 0.0f),
            toE = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.08f),
            toS = listOf(-0.05f, 0.30f, 0.0f, 0.05f, 0.0f),
            toTau = listOf(0.0f, 0.0f, 0.0f, 0.08f, 0.0f),
            toV = listOf(0.20f, -0.12f, 0.0f, 0.0f, 0.0f),
            toM = listOf(0.0f, 0.0f, 0.0f, 0.18f, 0.12f),
            toF = listOf(-0.20f, 0.12f, 0.0f, 0.0f, 0.0f),
        )
    }
}
