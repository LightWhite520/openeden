package io.openeden.runtime.affect

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta

data class PreTickResult(
    val original: BioVector,
    val preTicked: BioVector,
    val appliedDelta: VectorDelta,
    val skipped: Boolean,
)
