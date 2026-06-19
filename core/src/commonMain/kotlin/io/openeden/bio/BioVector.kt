package io.openeden.bio

import kotlinx.serialization.Serializable
import kotlin.math.abs

const val VECTOR_DIMENSIONS = 8

@Serializable
data class BioVector(
    val l: Float,
    val p: Float,
    val e: Float,
    val s: Float,
    val tau: Float,
    val v: Float,
    val m: Float,
    val f: Float,
) {
    init {
        require(toList().size == VECTOR_DIMENSIONS)
    }

    fun toList(): List<Float> = listOf(l, p, e, s, tau, v, m, f)

    fun derivedDissonance(): Float = abs(l - tau) * (1.0f - e)

    fun apply(delta: VectorDelta): BioVector = BioVector(
        l = (l + delta.l).coerceIn(0.0f, 1.0f),
        p = (p + delta.p).coerceIn(0.0f, 1.0f),
        e = (e + delta.e).coerceIn(0.0f, 1.0f),
        s = (s + delta.s).coerceIn(0.0f, 1.0f),
        tau = (tau + delta.tau).coerceIn(0.0f, 1.0f),
        v = (v + delta.v).coerceIn(0.0f, 1.0f),
        m = (m + delta.m).coerceIn(0.0f, 1.0f),
        f = (f + delta.f).coerceIn(0.0f, 1.0f),
    )

    companion object {
        val Neutral = BioVector(
            l = 0.5f,
            p = 0.5f,
            e = 0.5f,
            s = 0.5f,
            tau = 0.5f,
            v = 0.5f,
            m = 0.5f,
            f = 0.5f,
        )
    }
}

@Serializable
data class VectorDelta(
    val l: Float = 0.0f,
    val p: Float = 0.0f,
    val e: Float = 0.0f,
    val s: Float = 0.0f,
    val tau: Float = 0.0f,
    val v: Float = 0.0f,
    val m: Float = 0.0f,
    val f: Float = 0.0f,
) {
    fun toList(): List<Float> = listOf(l, p, e, s, tau, v, m, f)

    fun scale(factor: Float): VectorDelta = VectorDelta(
        l = l * factor,
        p = p * factor,
        e = e * factor,
        s = s * factor,
        tau = tau * factor,
        v = v * factor,
        m = m * factor,
        f = f * factor,
    )

    fun clampMagnitude(maxMagnitude: Float): VectorDelta = VectorDelta(
        l = l.coerceIn(-maxMagnitude, maxMagnitude),
        p = p.coerceIn(-maxMagnitude, maxMagnitude),
        e = e.coerceIn(-maxMagnitude, maxMagnitude),
        s = s.coerceIn(-maxMagnitude, maxMagnitude),
        tau = tau.coerceIn(-maxMagnitude, maxMagnitude),
        v = v.coerceIn(-maxMagnitude, maxMagnitude),
        m = m.coerceIn(-maxMagnitude, maxMagnitude),
        f = f.coerceIn(-maxMagnitude, maxMagnitude),
    )

    companion object {
        val Zero = VectorDelta()
    }
}
