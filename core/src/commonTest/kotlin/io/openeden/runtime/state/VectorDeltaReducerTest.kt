package io.openeden.runtime.state

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorDeltaReducerTest {
    private val reducer = VectorDeltaReducer()
    private val origin = uniformVector(0.5f)

    @Test
    fun `ordinary proposals approach neither storage boundary across all dimensions`() {
        for (dimension in Dimension.entries) {
            var upper = origin.with(dimension, 0.98f)
            var lower = origin.with(dimension, 0.02f)
            repeat(11) {
                upper = reducer.reduce(
                    current = upper,
                    origin = origin,
                    proposedDelta = delta(dimension, 0.1f),
                    context = VectorDeltaContext.Ordinary,
                ).result
                lower = reducer.reduce(
                    current = lower,
                    origin = origin,
                    proposedDelta = delta(dimension, -0.1f),
                    context = VectorDeltaContext.Ordinary,
                ).result
            }

            assertTrue(upper[dimension] in 0.98f..<0.99f, "$dimension pinned at the upper boundary")
            assertTrue(lower[dimension] in 0.01f..<0.02f, "$dimension pinned at the lower boundary")
        }
    }

    @Test
    fun `movement away from either boundary remains materially available in all dimensions`() {
        for (dimension in Dimension.entries) {
            val fromUpper = reducer.reduce(
                current = origin.with(dimension, 0.99f),
                origin = origin,
                proposedDelta = delta(dimension, -0.1f),
                context = VectorDeltaContext.Ordinary,
            )
            val fromLower = reducer.reduce(
                current = origin.with(dimension, 0.01f),
                origin = origin,
                proposedDelta = delta(dimension, 0.1f),
                context = VectorDeltaContext.Ordinary,
            )

            assertTrue(fromUpper.effectiveDelta[dimension] < -0.05f, "$dimension could not leave upper boundary")
            assertTrue(fromLower.effectiveDelta[dimension] > 0.05f, "$dimension could not leave lower boundary")
        }
    }

    @Test
    fun `homeostasis uses asymmetric origin mapping in internal space`() {
        val asymmetricOrigin = uniformVector(0.25f)
        val above = reducer.reduce(
            current = uniformVector(0.625f),
            origin = asymmetricOrigin,
            proposedDelta = VectorDelta.Zero,
            context = VectorDeltaContext.Ordinary,
            elapsedSeconds = 3_600.0f,
        )
        val below = reducer.reduce(
            current = uniformVector(0.125f),
            origin = asymmetricOrigin,
            proposedDelta = VectorDelta.Zero,
            context = VectorDeltaContext.Ordinary,
            elapsedSeconds = 3_600.0f,
        )

        assertTrue(above.result.p in 0.25f..<0.625f)
        assertTrue(below.result.p in 0.125f..<0.25f)
        assertEquals(
            3.0f,
            abs(above.homeostaticDelta.p) / abs(below.homeostaticDelta.p),
            absoluteTolerance = 0.01f,
        )
    }

    @Test
    fun `neutral dead zone suppresses tiny ordinary proposal jitter`() {
        val reduction = reducer.reduce(
            current = origin,
            origin = origin,
            proposedDelta = uniformDelta(0.004f),
            context = VectorDeltaContext.Ordinary,
        )

        assertEquals(VectorDelta.Zero, reduction.effectiveDelta)
        assertEquals(origin, reduction.result)
        assertTrue(reduction.reasons.any { it.contains("dead_zone") })
    }

    @Test
    fun `zero elapsed time adds no homeostatic pull`() {
        val reduction = reducer.reduce(
            current = uniformVector(0.7f),
            origin = uniformVector(0.3f),
            proposedDelta = VectorDelta.Zero,
            context = VectorDeltaContext.Ordinary,
            elapsedSeconds = 0.0f,
        )

        assertEquals(VectorDelta.Zero, reduction.homeostaticDelta)
        assertEquals(uniformVector(0.7f), reduction.result)
    }

    @Test
    fun `large elapsed time is bounded and cannot cross the dynamic origin`() {
        val dynamicOrigin = uniformVector(0.3f)
        val reduction = reducer.reduce(
            current = uniformVector(0.9f),
            origin = dynamicOrigin,
            proposedDelta = VectorDelta.Zero,
            context = VectorDeltaContext.Ordinary,
            elapsedSeconds = 1_000_000_000.0f,
        )

        assertTrue(reduction.result.p in 0.3f..<0.9f)
        assertTrue(abs(reduction.homeostaticDelta.p) <= 0.15f)
        assertTrue(reduction.reasons.any { it.contains("elapsed") })
    }

    @Test
    fun `authoritative confidence provides a bounded stronger gain`() {
        val ordinary = reducer.reduce(
            current = origin,
            origin = origin,
            proposedDelta = VectorDelta(p = 0.1f),
            context = VectorDeltaContext.Ordinary,
        )
        val authoritative = reducer.reduce(
            current = origin,
            origin = origin,
            proposedDelta = VectorDelta(p = 0.1f),
            context = VectorDeltaContext.Authoritative(confidence = 1.0f),
        )

        assertTrue(authoritative.effectiveDelta.p > ordinary.effectiveDelta.p)
        assertTrue(authoritative.effectiveDelta.p <= 0.050001f)
    }

    @Test
    fun `invalid model proposals are rejected without nonfinite output`() {
        val invalidValues = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.01f, -1.01f)
        for (dimension in Dimension.entries) {
            for (invalid in invalidValues) {
                val reduction = reducer.reduce(
                    current = origin,
                    origin = origin,
                    proposedDelta = delta(dimension, invalid),
                    context = VectorDeltaContext.Ordinary,
                )

                assertEquals(VectorDelta.Zero, reduction.effectiveDelta)
                assertEquals(VectorDelta.Zero, reduction.homeostaticDelta)
                assertEquals(origin, reduction.result)
                assertTrue(reduction.result.toList().all(Float::isFinite))
                assertTrue(reduction.reasons.any { it.contains("proposal_rejected") })
            }
        }
    }

    @Test
    fun `invalid current or origin coordinates degrade to finite rejected mechanics`() {
        for (dimension in Dimension.entries) {
            val invalidCurrent = reducer.reduce(
                current = origin.with(dimension, Float.NaN),
                origin = origin,
                proposedDelta = delta(dimension, 0.1f),
                context = VectorDeltaContext.Ordinary,
            )
            val invalidOrigin = reducer.reduce(
                current = origin,
                origin = origin.with(dimension, Float.POSITIVE_INFINITY),
                proposedDelta = delta(dimension, 0.1f),
                context = VectorDeltaContext.Ordinary,
            )

            assertTrue(invalidCurrent.result.toList().all(Float::isFinite))
            assertEquals(VectorDelta.Zero, invalidCurrent.effectiveDelta)
            assertEquals(origin, invalidOrigin.result)
            assertEquals(VectorDelta.Zero, invalidOrigin.effectiveDelta)
        }
    }

    @Test
    fun `invalid elapsed and confidence fall back without turn failure`() {
        val invalidElapsed = reducer.reduce(
            current = origin,
            origin = origin,
            proposedDelta = VectorDelta(p = 0.1f),
            context = VectorDeltaContext.Ordinary,
            elapsedSeconds = Float.NaN,
        )
        val ordinary = reducer.reduce(
            current = origin,
            origin = origin,
            proposedDelta = VectorDelta(p = 0.1f),
            context = VectorDeltaContext.Ordinary,
        )
        val invalidConfidence = reducer.reduce(
            current = origin,
            origin = origin,
            proposedDelta = VectorDelta(p = 0.1f),
            context = VectorDeltaContext.Authoritative(Float.NaN),
        )

        assertEquals(VectorDelta.Zero, invalidElapsed.homeostaticDelta)
        assertEquals(ordinary.effectiveDelta, invalidElapsed.effectiveDelta)
        assertEquals(ordinary.effectiveDelta, invalidConfidence.effectiveDelta)
        assertTrue(invalidElapsed.reasons.any { it.contains("elapsed_rejected") })
        assertTrue(invalidConfidence.reasons.any { it.contains("confidence_rejected") })
    }

    @Test
    fun `neutral fixture median absolute effective delta is at most two hundredths`() {
        val proposals = listOf(-0.03f, -0.02f, -0.01f, -0.004f, 0.0f, 0.004f, 0.01f, 0.02f, 0.03f)
        val effective = proposals.map { proposal ->
            abs(
                reducer.reduce(
                    current = origin,
                    origin = origin,
                    proposedDelta = VectorDelta(p = proposal),
                    context = VectorDeltaContext.Ordinary,
                ).effectiveDelta.p,
            )
        }.sorted()

        assertTrue(effective[effective.size / 2] <= 0.02f)
    }
}

private enum class Dimension { L, P, E, S, TAU, V, M, F }

private operator fun BioVector.get(dimension: Dimension): Float = when (dimension) {
    Dimension.L -> l
    Dimension.P -> p
    Dimension.E -> e
    Dimension.S -> s
    Dimension.TAU -> tau
    Dimension.V -> v
    Dimension.M -> m
    Dimension.F -> f
}

private operator fun VectorDelta.get(dimension: Dimension): Float = when (dimension) {
    Dimension.L -> l
    Dimension.P -> p
    Dimension.E -> e
    Dimension.S -> s
    Dimension.TAU -> tau
    Dimension.V -> v
    Dimension.M -> m
    Dimension.F -> f
}

private fun BioVector.with(dimension: Dimension, value: Float): BioVector = when (dimension) {
    Dimension.L -> copy(l = value)
    Dimension.P -> copy(p = value)
    Dimension.E -> copy(e = value)
    Dimension.S -> copy(s = value)
    Dimension.TAU -> copy(tau = value)
    Dimension.V -> copy(v = value)
    Dimension.M -> copy(m = value)
    Dimension.F -> copy(f = value)
}

private fun delta(dimension: Dimension, value: Float): VectorDelta = when (dimension) {
    Dimension.L -> VectorDelta(l = value)
    Dimension.P -> VectorDelta(p = value)
    Dimension.E -> VectorDelta(e = value)
    Dimension.S -> VectorDelta(s = value)
    Dimension.TAU -> VectorDelta(tau = value)
    Dimension.V -> VectorDelta(v = value)
    Dimension.M -> VectorDelta(m = value)
    Dimension.F -> VectorDelta(f = value)
}

private fun uniformVector(value: Float): BioVector = BioVector(value, value, value, value, value, value, value, value)

private fun uniformDelta(value: Float): VectorDelta = VectorDelta(value, value, value, value, value, value, value, value)
