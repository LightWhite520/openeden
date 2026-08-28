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
    val lineage: MemoryLineage = MemoryLineage.Empty,
    val contentFingerprint: String? = null,
    val incarnationId: String = "",
    val sourceSessionId: String = "",
    val canonicalSubjectId: String = "",
    val visibility: MemoryVisibility = MemoryVisibility.ScopeShared(""),
    val platform: String = "",
)
