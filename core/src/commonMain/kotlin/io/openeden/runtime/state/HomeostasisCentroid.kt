package io.openeden.runtime.state

import io.openeden.runtime.session.SessionStateStore

import io.openeden.bio.BioVector
import io.openeden.memory.MemoryStore

fun interface HomeostasisCentroidProvider {
    suspend fun centroidFor(sessionId: String): BioVector
}

class StoredOriginCentroidProvider(
    private val store: SessionStateStore,
) : HomeostasisCentroidProvider {
    override suspend fun centroidFor(sessionId: String): BioVector =
        store.read(sessionId).origin
}

class SlidingWindowHomeostasisCentroidProvider(
    private val memoryStore: MemoryStore,
    private val fallback: HomeostasisCentroidProvider,
    private val windowSize: Int = 32,
    private val maxMovementPerDimension: Float = 0.25f,
) : HomeostasisCentroidProvider {
    override suspend fun centroidFor(sessionId: String): BioVector {
        val vectors = memoryStore.stableVectors(sessionId, windowSize)
        val previous = fallback.centroidFor(sessionId)
        if (vectors.isEmpty()) return previous
        val candidate = BioVector(
            l = vectors.map { it.l }.averageFloat(),
            p = vectors.map { it.p }.averageFloat(),
            e = vectors.map { it.e }.averageFloat(),
            s = vectors.map { it.s }.averageFloat(),
            tau = vectors.map { it.tau }.averageFloat(),
            v = vectors.map { it.v }.averageFloat(),
            m = vectors.map { it.m }.averageFloat(),
            f = vectors.map { it.f }.averageFloat(),
        )
        return BioVector(
            l = bounded(previous.l, candidate.l),
            p = bounded(previous.p, candidate.p),
            e = bounded(previous.e, candidate.e),
            s = bounded(previous.s, candidate.s),
            tau = bounded(previous.tau, candidate.tau),
            v = bounded(previous.v, candidate.v),
            m = bounded(previous.m, candidate.m),
            f = bounded(previous.f, candidate.f),
        )
    }

    private fun bounded(previous: Float, candidate: Float): Float =
        candidate.coerceIn(
            previous - maxMovementPerDimension.coerceAtLeast(0.0f),
            previous + maxMovementPerDimension.coerceAtLeast(0.0f),
        ).coerceIn(0.0f, 1.0f)
}

private fun List<Float>.averageFloat(): Float =
    if (isEmpty()) 0.0f else (sum() / size).coerceIn(0.0f, 1.0f)
