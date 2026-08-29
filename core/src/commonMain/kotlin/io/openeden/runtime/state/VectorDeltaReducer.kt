package io.openeden.runtime.state

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.bio.VectorMapping
import kotlin.math.abs
import kotlin.math.exp

class VectorDeltaReducer {
    fun reduce(
        current: BioVector,
        origin: BioVector,
        proposedDelta: VectorDelta,
        context: VectorDeltaContext,
        elapsedSeconds: Float = 0.0f,
    ): VectorDeltaReduction {
        val reasons = linkedSetOf<String>()
        val currentValues = current.values()
        val originValues = origin.values()
        val proposedValues = proposedDelta.values()
        val safeCurrent = sanitizeCurrent(currentValues, originValues)

        if (!currentValues.all(::isStorageCoordinate)) {
            reasons += REASON_CURRENT_REJECTED
        }
        if (!originValues.all(::isStorageCoordinate)) {
            reasons += REASON_ORIGIN_REJECTED
        }
        if (reasons.isNotEmpty()) {
            return rejected(proposedDelta, safeCurrent, reasons)
        }
        if (!proposedValues.all(::isProposalCoordinate)) {
            reasons += REASON_PROPOSAL_REJECTED
            return rejected(proposedDelta, safeCurrent, reasons)
        }

        val safeElapsed = when {
            !elapsedSeconds.isFinite() || elapsedSeconds < 0.0f -> {
                reasons += REASON_ELAPSED_REJECTED
                0.0f
            }
            elapsedSeconds > MAX_ELAPSED_SECONDS -> {
                reasons += REASON_ELAPSED_CAPPED
                MAX_ELAPSED_SECONDS
            }
            else -> elapsedSeconds
        }
        val gain = when (context) {
            VectorDeltaContext.Ordinary -> ORDINARY_GAIN
            is VectorDeltaContext.Authoritative -> {
                if (!context.confidence.isFinite() || context.confidence !in 0.0f..1.0f) {
                    reasons += REASON_CONFIDENCE_REJECTED
                    ORDINARY_GAIN
                } else {
                    reasons += REASON_AUTHORITATIVE_GAIN
                    ORDINARY_GAIN + (MAX_AUTHORITATIVE_GAIN - ORDINARY_GAIN) * context.confidence
                }
            }
        }

        val effectiveValues = FloatArray(DIMENSION_COUNT)
        val proposedResultValues = FloatArray(DIMENSION_COUNT)
        for (index in 0 until DIMENSION_COUNT) {
            val proposed = proposedValues[index]
            val clamped = proposed.coerceIn(-MAX_PROPOSAL_MAGNITUDE, MAX_PROPOSAL_MAGNITUDE)
            if (clamped != proposed) reasons += "${DIMENSION_NAMES[index]}:proposal_clamped"
            val afterDeadZone = if (abs(clamped) <= NEUTRAL_DEAD_ZONE) {
                if (clamped != 0.0f) reasons += "${DIMENSION_NAMES[index]}:dead_zone"
                0.0f
            } else {
                clamped
            }
            val effective = boundaryAwareDelta(
                current = currentValues[index],
                origin = originValues[index],
                gainedDelta = afterDeadZone * gain,
                onDamped = { reasons += "${DIMENSION_NAMES[index]}:boundary_damped" },
            )
            val result = (currentValues[index] + effective).coerceIn(0.0f, 1.0f)
            effectiveValues[index] = result - currentValues[index]
            proposedResultValues[index] = result
        }

        val pullFraction = homeostaticPullFraction(safeElapsed)
        val resultValues = proposedResultValues.copyOf()
        val homeostaticValues = FloatArray(DIMENSION_COUNT)
        if (pullFraction > 0.0f) {
            reasons += REASON_HOMEOSTASIS_APPLIED
            for (index in 0 until DIMENSION_COUNT) {
                val internal = VectorMapping.storageToInternal(proposedResultValues[index], originValues[index])
                val pulled = internal * (1.0f - pullFraction)
                val result = VectorMapping.internalToStorage(pulled, originValues[index])
                resultValues[index] = result
                homeostaticValues[index] = result - proposedResultValues[index]
            }
        }

        val resultVector = resultValues.toVector()
        return VectorDeltaReduction(
            proposedDelta = proposedDelta,
            effectiveDelta = effectiveValues.toDelta(),
            homeostaticDelta = homeostaticValues.toDelta(),
            committedDelta = current.deltaTo(resultVector),
            result = resultVector,
            reasons = reasons,
        )
    }

    private fun boundaryAwareDelta(
        current: Float,
        origin: Float,
        gainedDelta: Float,
        onDamped: () -> Unit,
    ): Float {
        if (gainedDelta == 0.0f) return 0.0f
        val displacement = current - origin
        val movesAwayFromOrigin = displacement == 0.0f || displacement * gainedDelta > 0.0f
        if (movesAwayFromOrigin) {
            onDamped()
            val headroom = if (gainedDelta > 0.0f) 1.0f - current else current
            return gainedDelta * headroom.coerceIn(0.0f, 1.0f)
        }

        val distanceToOrigin = abs(displacement)
        if (abs(gainedDelta) <= distanceToOrigin) return gainedDelta

        onDamped()
        val direction = if (gainedDelta > 0.0f) 1.0f else -1.0f
        val overshoot = abs(gainedDelta) - distanceToOrigin
        val headroomFromOrigin = if (direction > 0.0f) 1.0f - origin else origin
        return direction * (distanceToOrigin + overshoot * headroomFromOrigin.coerceIn(0.0f, 1.0f))
    }

    private fun homeostaticPullFraction(elapsedSeconds: Float): Float {
        if (elapsedSeconds == 0.0f) return 0.0f
        val exponentialPull = 1.0 - exp(-elapsedSeconds.toDouble() / HOMEOSTASIS_TIME_CONSTANT_SECONDS)
        return exponentialPull.toFloat().coerceIn(0.0f, MAX_HOMEOSTATIC_PULL)
    }

    private fun rejected(
        proposedDelta: VectorDelta,
        safeCurrent: BioVector,
        reasons: Set<String>,
    ): VectorDeltaReduction = VectorDeltaReduction(
        proposedDelta = proposedDelta,
        effectiveDelta = VectorDelta.Zero,
        homeostaticDelta = VectorDelta.Zero,
        committedDelta = VectorDelta.Zero,
        result = safeCurrent,
        reasons = reasons,
    )

    private fun sanitizeCurrent(current: FloatArray, origin: FloatArray): BioVector =
        FloatArray(DIMENSION_COUNT) { index ->
            when {
                isStorageCoordinate(current[index]) -> current[index]
                current[index].isFinite() -> current[index].coerceIn(0.0f, 1.0f)
                isStorageCoordinate(origin[index]) -> origin[index]
                else -> SAFE_CORRUPTION_FALLBACK
            }
        }.toVector()

    private fun isStorageCoordinate(value: Float): Boolean = value.isFinite() && value in 0.0f..1.0f

    private fun isProposalCoordinate(value: Float): Boolean = value.isFinite() && value in -1.0f..1.0f

    private companion object {
        const val DIMENSION_COUNT = 8
        const val MAX_PROPOSAL_MAGNITUDE = 0.25f
        const val NEUTRAL_DEAD_ZONE = 0.005f
        const val ORDINARY_GAIN = 0.6f
        const val MAX_AUTHORITATIVE_GAIN = 1.0f
        const val MAX_ELAPSED_SECONDS = 86_400.0f
        const val HOMEOSTASIS_TIME_CONSTANT_SECONDS = 21_600.0
        const val MAX_HOMEOSTATIC_PULL = 0.25f
        const val SAFE_CORRUPTION_FALLBACK = 0.5f
        const val REASON_CURRENT_REJECTED = "current_rejected"
        const val REASON_ORIGIN_REJECTED = "origin_rejected"
        const val REASON_PROPOSAL_REJECTED = "proposal_rejected"
        const val REASON_ELAPSED_REJECTED = "elapsed_rejected"
        const val REASON_ELAPSED_CAPPED = "elapsed_capped"
        const val REASON_CONFIDENCE_REJECTED = "authoritative_confidence_rejected"
        const val REASON_AUTHORITATIVE_GAIN = "authoritative_gain"
        const val REASON_HOMEOSTASIS_APPLIED = "homeostasis_applied"
        val DIMENSION_NAMES = arrayOf("L", "P", "E", "S", "tau", "V", "M", "F")
    }
}

private fun BioVector.values(): FloatArray = floatArrayOf(l, p, e, s, tau, v, m, f)

private fun VectorDelta.values(): FloatArray = floatArrayOf(l, p, e, s, tau, v, m, f)

private fun FloatArray.toVector(): BioVector = BioVector(
    l = this[0],
    p = this[1],
    e = this[2],
    s = this[3],
    tau = this[4],
    v = this[5],
    m = this[6],
    f = this[7],
)

private fun FloatArray.toDelta(): VectorDelta = VectorDelta(
    l = this[0],
    p = this[1],
    e = this[2],
    s = this[3],
    tau = this[4],
    v = this[5],
    m = this[6],
    f = this[7],
)

private fun BioVector.deltaTo(target: BioVector): VectorDelta = VectorDelta(
    l = target.l - l,
    p = target.p - p,
    e = target.e - e,
    s = target.s - s,
    tau = target.tau - tau,
    v = target.v - v,
    m = target.m - m,
    f = target.f - f,
)
