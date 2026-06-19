package io.openeden.bio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

class BioVectorTest {
    @Test
    fun `derived dissonance is calculated from L tau and E only`() {
        val vector = BioVector(
            l = 0.8f,
            p = 0.1f,
            e = 0.25f,
            s = 0.2f,
            tau = 0.2f,
            v = 0.9f,
            m = 0.4f,
            f = 0.7f,
        )

        assertEquals(0.45f, vector.derivedDissonance(), absoluteTolerance = 0.0001f)
        assertEquals(VECTOR_DIMENSIONS, vector.toList().size)
        assertEquals(VECTOR_DIMENSIONS, VectorDelta.Zero.toList().size)
    }

    @Test
    fun `piecewise mapping round trips through centroid origin`() {
        val origin = 0.35f
        val raw = 0.82f

        val internal = VectorMapping.storageToInternal(raw, origin)
        val restored = VectorMapping.internalToStorage(internal, origin)

        assertEquals(raw, restored, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `center symmetric target mirrors around dynamic origin`() {
        val origin = BioVector.Neutral.copy(p = 0.4f, v = 0.3f)
        val vector = BioVector.Neutral.copy(p = 0.2f, v = 0.65f)

        val target = VectorMapping.centerSymmetricTarget(vector, origin)
        val originalInternal = VectorMapping.toInternal(vector, origin)
        val targetInternal = VectorMapping.toInternal(target, origin)

        assertEquals(0.0f, originalInternal.p + targetInternal.p, absoluteTolerance = 0.0001f)
        assertEquals(0.0f, originalInternal.v + targetInternal.v, absoluteTolerance = 0.0001f)
        assert(abs(target.p - origin.p) > 0.0f)
        assert(abs(target.v - origin.v) > 0.0f)
    }
}
