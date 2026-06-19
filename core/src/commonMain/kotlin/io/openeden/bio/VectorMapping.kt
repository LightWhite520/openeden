package io.openeden.bio

object VectorMapping {
    fun storageToInternal(raw: Float, origin: Float): Float {
        val safeOrigin = origin.coerceIn(0.0001f, 0.9999f)
        val safeRaw = raw.coerceIn(0.0f, 1.0f)
        return if (safeRaw >= safeOrigin) {
            (safeRaw - safeOrigin) / (1.0f - safeOrigin)
        } else {
            (safeRaw - safeOrigin) / safeOrigin
        }.coerceIn(-1.0f, 1.0f)
    }

    fun internalToStorage(internal: Float, origin: Float): Float {
        val safeOrigin = origin.coerceIn(0.0001f, 0.9999f)
        val safeInternal = internal.coerceIn(-1.0f, 1.0f)
        return if (safeInternal >= 0.0f) {
            safeOrigin + safeInternal * (1.0f - safeOrigin)
        } else {
            safeOrigin + safeInternal * safeOrigin
        }.coerceIn(0.0f, 1.0f)
    }

    fun toInternal(vector: BioVector, origin: BioVector): InternalBioVector = InternalBioVector(
        l = storageToInternal(vector.l, origin.l),
        p = storageToInternal(vector.p, origin.p),
        e = storageToInternal(vector.e, origin.e),
        s = storageToInternal(vector.s, origin.s),
        tau = storageToInternal(vector.tau, origin.tau),
        v = storageToInternal(vector.v, origin.v),
        m = storageToInternal(vector.m, origin.m),
        f = storageToInternal(vector.f, origin.f),
    )

    fun centerSymmetricTarget(vector: BioVector, origin: BioVector): BioVector {
        val internal = toInternal(vector, origin)
        return BioVector(
            l = internalToStorage(-internal.l, origin.l),
            p = internalToStorage(-internal.p, origin.p),
            e = internalToStorage(-internal.e, origin.e),
            s = internalToStorage(-internal.s, origin.s),
            tau = internalToStorage(-internal.tau, origin.tau),
            v = internalToStorage(-internal.v, origin.v),
            m = internalToStorage(-internal.m, origin.m),
            f = internalToStorage(-internal.f, origin.f),
        )
    }
}

data class InternalBioVector(
    val l: Float,
    val p: Float,
    val e: Float,
    val s: Float,
    val tau: Float,
    val v: Float,
    val m: Float,
    val f: Float,
)
