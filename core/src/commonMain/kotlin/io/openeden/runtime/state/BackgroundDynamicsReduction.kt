package io.openeden.runtime.state

import io.openeden.bio.BioVector
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState

data class BackgroundDynamicsReduction(
    val vector: BioVector,
    val shockState: ShockState?,
    val omega: OmegaState,
    val consumedAtMs: Long?,
    val elapsedMillis: Long,
    val baseline: Boolean,
    val shockDecayed: Boolean,
    val omegaChanged: Boolean,
)
