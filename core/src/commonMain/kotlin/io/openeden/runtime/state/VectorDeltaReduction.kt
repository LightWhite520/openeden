package io.openeden.runtime.state

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta

data class VectorDeltaReduction(
    val proposedDelta: VectorDelta,
    val effectiveDelta: VectorDelta,
    val homeostaticDelta: VectorDelta,
    val committedDelta: VectorDelta,
    val result: BioVector,
    val reasons: Set<String>,
)
