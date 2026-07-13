package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import kotlinx.serialization.Serializable

@Serializable
data class MemoryMetadata(
    val snapshot8D: BioVector,
    val omegaState: Float,
    val deltaVec: VectorDelta,
    val snapshotOrigin: BioVector,
    val userId: String,
)
