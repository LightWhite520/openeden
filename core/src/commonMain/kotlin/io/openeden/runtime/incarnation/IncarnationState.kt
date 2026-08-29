package io.openeden.runtime.incarnation

import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState

data class IncarnationState(
    val incarnationId: String,
    val vector: BioVector,
    val origin: BioVector,
    val omega: OmegaState,
    val evolutionIndex: Long,
    val personaMode: PersonaMode,
    val personaStartSubState: PersonaSubState,
    val lastUserActivityMs: Long?,
    val lastRuntimeTickAtMs: Long?,
    val shockState: ShockState?,
    val lastVectorDynamicsAtMs: Long? = null,
    val centroidRevision: Long = 0L,
    val originRevision: Long = 0L,
) {
    init {
        require(personaMode != PersonaMode.LEGACY || personaStartSubState == PersonaSubState.AWAKENED) {
            "Legacy incarnation mode only supports the awakened starting point"
        }
        require(centroidRevision >= 0L) { "Centroid revision must be non-negative" }
        require(originRevision in 0L..centroidRevision) {
            "Origin revision must be non-negative and no greater than centroid revision"
        }
    }
}
