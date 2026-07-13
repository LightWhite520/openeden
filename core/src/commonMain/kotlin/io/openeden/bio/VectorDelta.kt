package io.openeden.bio

import kotlinx.serialization.Serializable

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
