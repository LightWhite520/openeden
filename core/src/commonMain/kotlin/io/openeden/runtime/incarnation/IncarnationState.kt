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
) {
    init {
        require(personaMode != PersonaMode.LEGACY || personaStartSubState == PersonaSubState.AWAKENED) {
            "Legacy incarnation mode only supports the awakened starting point"
        }
    }
}
